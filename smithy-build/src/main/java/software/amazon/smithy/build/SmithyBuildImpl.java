/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.build;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import software.amazon.smithy.build.model.ProjectionConfig;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.build.model.TransformConfig;
import software.amazon.smithy.build.transforms.Apply;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.transform.ModelTransformer;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.utils.Pair;
import software.amazon.smithy.utils.SmithyBuilder;

final class SmithyBuildImpl {
    private static final Logger LOGGER = Logger.getLogger(SmithyBuild.class.getName());
    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9\\-_.]+$");

    private final SmithyBuildConfig config;
    private final Function<Path, FileManifest> fileManifestFactory;
    private final Supplier<ModelAssembler> modelAssemblerSupplier;
    private final Path outputDirectory;
    private final Map<String, List<Pair<ObjectNode, ProjectionTransformer>>> transformers = new HashMap<>();
    private final ModelTransformer modelTransformer;
    private final Function<String, Optional<ProjectionTransformer>> transformFactory;
    private final Function<String, Optional<SmithyBuildPlugin>> pluginFactory;
    private final Model model;
    private final ClassLoader pluginClassLoader;
    private final Set<Path> sources;
    private final Predicate<String> projectionFilter;
    private final Predicate<String> pluginFilter;

    SmithyBuildImpl(SmithyBuild builder) {
        config = prepareConfig(SmithyBuilder.requiredState("config", builder.config));
        sources = builder.sources;
        fileManifestFactory = builder.fileManifestFactory != null
                ? builder.fileManifestFactory
                : FileManifest::create;
        modelAssemblerSupplier = builder.modelAssemblerSupplier != null
                ? builder.modelAssemblerSupplier
                : Model::assembler;
        modelTransformer = builder.modelTransformer != null
                ? builder.modelTransformer
                : ModelTransformer.create();
        transformFactory = builder.transformFactory != null
                ? builder.transformFactory
                : ProjectionTransformer.createServiceFactory(getClass().getClassLoader());
        pluginFactory = builder.pluginFactory != null
                ? builder.pluginFactory
                : SmithyBuildPlugin.createServiceFactory(getClass().getClassLoader());
        model = builder.model != null
                ? builder.model
                : Model.builder().build();

        if (builder.outputDirectory != null) {
            outputDirectory = builder.outputDirectory;
        } else if (config.getOutputDirectory().isPresent()) {
            outputDirectory = Paths.get(config.getOutputDirectory().get());
        } else {
            // Default the output directory to the current working directory + "./build/smithy"
            outputDirectory = Paths.get(".").toAbsolutePath().normalize().resolve("build").resolve("smithy");
        }

        // Create the transformers for each projection.
        config.getProjections().forEach((projectionName, projectionConfig) -> {
            transformers.put(projectionName, createTransformers(projectionName, projectionConfig));
        });

        pluginClassLoader = builder.pluginClassLoader;
        projectionFilter = builder.projectionFilter;
        pluginFilter = builder.pluginFilter;
    }

    private static SmithyBuildConfig prepareConfig(SmithyBuildConfig config) {
        // If we don't have a source projection specified, supply one.
        if (!config.getProjections().containsKey("source")) {
            Map<String, ProjectionConfig> projections = new HashMap<>(config.getProjections());
            projections.put("source", ProjectionConfig.builder().build());
            config = config.toBuilder().projections(projections).build();
        }

        // The `source` projection cannot include mappers or filters.
        ProjectionConfig sourceProjection = config.getProjections().get("source");
        if (!sourceProjection.getTransforms().isEmpty()) {
            throw new SmithyBuildException("The source projection cannot contain any transforms");
        }

        config.getPlugins().keySet().forEach(p -> validatePluginName("[top-level]", p));

        for (Map.Entry<String, ProjectionConfig> entry : config.getProjections().entrySet()) {
            String projectionName = entry.getKey();
            if (!PATTERN.matcher(projectionName).matches()) {
                throw new SmithyBuildException(String.format("Invalid Smithy build projection name `%s`. "
                        + "Projection names must match the following regex: %s", projectionName, PATTERN));
            }
            entry.getValue().getPlugins().keySet().forEach(p -> validatePluginName(entry.getKey(), p));
            entry.getValue().getTransforms().forEach(t -> validateTransformName(entry.getKey(), t.getName()));
        }

        return config;
    }

    private static void validateTransformName(String projection, String transformName) {
        if (!PATTERN.matcher(transformName).matches()) {
            throw new SmithyBuildException(String.format("Invalid transform name `%s` found in the `%s` projection. "
                    + " Transform names must match the following regex: %s", transformName, projection, PATTERN));
        }
    }

    private static void validatePluginName(String projection, String plugin) {
        if (!PATTERN.matcher(plugin).matches()) {
            throw new SmithyBuildException(String.format(
                    "Invalid plugin name `%s` found in the `%s` projection. "
                    + " Plugin names must match the following regex: %s", plugin, projection, PATTERN));
        }
    }

    void applyAllProjections(
            Consumer<ProjectionResult> projectionResultConsumer,
            BiConsumer<String, Throwable> projectionExceptionConsumer
    ) {
        Model resolvedModel = createBaseModel();

        // The projections are being split up here because we need to be able
        // to break out non-parallelizeable plugins. Right now the only
        // parallelization that occurs is at the projection level.
        List<Callable<Void>> parallelProjections = new ArrayList<>();
        List<String> parallelProjectionNameOrder = new ArrayList<>();

        for (Map.Entry<String, ProjectionConfig> entry : config.getProjections().entrySet()) {
            String name = entry.getKey();
            ProjectionConfig config = entry.getValue();

            if (config.isAbstract() || !projectionFilter.test(name)) {
                continue;
            }

            // Check to see if any of the plugins in the projection require the projection be run serially
            boolean isSerial = resolvePlugins(config).keySet().stream().anyMatch(pluginName -> {
                Optional<SmithyBuildPlugin> plugin = pluginFactory.apply(pluginName);
                return plugin.isPresent() && plugin.get().isSerial();
            });

            if (isSerial) {
                executeSerialProjection(resolvedModel, name, config,
                                        projectionResultConsumer, projectionExceptionConsumer);
            } else {
                parallelProjectionNameOrder.add(name);
                parallelProjections.add(() -> {
                    executeSerialProjection(resolvedModel, name, config,
                                            projectionResultConsumer, projectionExceptionConsumer);
                    return null;
                });
            }
        }

        // Common case of only executing a single plugin per/projection.
        if (parallelProjections.size() == 1) {
            try {
                parallelProjections.get(0).call();
            } catch (Throwable e) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                } else {
                    throw new RuntimeException(e);
                }
            }
        } else if (!parallelProjections.isEmpty()) {
            executeParallelProjections(parallelProjections, parallelProjectionNameOrder, projectionExceptionConsumer);
        }
    }

    private void executeSerialProjection(
            Model resolvedModel,
            String name,
            ProjectionConfig config,
            Consumer<ProjectionResult> projectionResultConsumer,
            BiConsumer<String, Throwable> projectionExceptionConsumer
    ) {
        // Errors that occur while invoking the result callback must not
        // cause the exception callback to be invoked.
        ProjectionResult result = null;

        try {
            result = applyProjection(name, config, resolvedModel);
        } catch (Throwable e) {
            projectionExceptionConsumer.accept(name, e);
        }

        if (result != null) {
            projectionResultConsumer.accept(result);
        }
    }

    private void executeParallelProjections(
            List<Callable<Void>> parallelProjections,
            List<String> parallelProjectionNameOrder,
            BiConsumer<String, Throwable> projectionExceptionConsumer
    ) {
        ExecutorService executor = ForkJoinPool.commonPool();

        try {
            List<Future<Void>> futures = executor.invokeAll(parallelProjections);
            // Futures are returned in the same order they were added, so
            // use the list of ordered names to know which projections failed.
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String failedProjectionName = parallelProjectionNameOrder.get(i);
                    projectionExceptionConsumer.accept(failedProjectionName, cause);
                }
            }
        } catch (InterruptedException e) {
            throw new SmithyBuildException(e.getMessage(), e);
        }
    }

    private Model createBaseModel() {
        Model resolvedModel = model;

        if (!config.getImports().isEmpty()) {
            LOGGER.fine(() -> "Merging the following imports into the loaded model: " + config.getImports());
            ModelAssembler assembler = modelAssemblerSupplier.get().addModel(model);
            config.getImports().forEach(assembler::addImport);
            resolvedModel = assembler.assemble().unwrap();
        }

        return resolvedModel;
    }

    private ProjectionResult applyProjection(String projectionName, ProjectionConfig projection, Model resolvedModel) {
        LOGGER.fine(() -> String.format("Creating the `%s` projection", projectionName));

        // Resolve imports.
        if (!projection.getImports().isEmpty()) {
            LOGGER.fine(() -> String.format(
                    "Merging the following `%s` projection imports into the loaded model: %s",
                    projectionName, projection.getImports()));
            ModelAssembler assembler = modelAssemblerSupplier.get().addModel(resolvedModel);
            projection.getImports().forEach(assembler::addImport);
            ValidatedResult<Model> resolvedResult = assembler.assemble();

            // Fail if the model can't be merged with the imports.
            if (!resolvedResult.getResult().isPresent()) {
                LOGGER.severe(String.format(
                        "The model could not be merged with the following imports: [%s]",
                        projection.getImports()));
                return ProjectionResult.builder()
                        .projectionName(projectionName)
                        .events(resolvedResult.getValidationEvents())
                        .build();
            }

            resolvedModel = resolvedResult.unwrap();
        }

        // Create the base directory where all projection artifacts are stored.
        Path baseProjectionDir = outputDirectory.resolve(projectionName);

        // Transform the model and collect the results.
        Model projectedModel = applyProjectionTransforms(
                resolvedModel, resolvedModel, projectionName, Collections.emptySet());

        ValidatedResult<Model> modelResult = modelAssemblerSupplier.get().addModel(projectedModel).assemble();

        ProjectionResult.Builder resultBuilder = ProjectionResult.builder()
                .projectionName(projectionName)
                .model(projectedModel)
                .events(modelResult.getValidationEvents());

        for (Map.Entry<String, ObjectNode> entry : resolvePlugins(projection).entrySet()) {
            if (pluginFilter.test(entry.getKey())) {
                applyPlugin(projectionName, projection, baseProjectionDir, entry.getKey(), entry.getValue(),
                            projectedModel, resolvedModel, modelResult, resultBuilder);
            }
        }

        return resultBuilder.build();
    }

    private Model applyProjectionTransforms(
            Model inputModel,
            Model originalModel,
            String projectionName,
            Set<String> visited
    ) {
        // Transform the model and collect the results.
        Model projectedModel = inputModel;

        for (Pair<ObjectNode, ProjectionTransformer> transformerBinding : transformers.get(projectionName)) {
            TransformContext context = TransformContext.builder()
                    .model(projectedModel)
                    .originalModel(originalModel)
                    .transformer(modelTransformer)
                    .projectionName(projectionName)
                    .sources(sources)
                    .settings(transformerBinding.left)
                    .visited(visited)
                    .build();
            projectedModel = transformerBinding.right.transform(context);
        }

        return projectedModel;
    }

    private void applyPlugin(
            String projectionName,
            ProjectionConfig projection,
            Path baseProjectionDir,
            String pluginName,
            ObjectNode pluginSettings,
            Model projectedModel,
            Model resolvedModel,
            ValidatedResult<Model> modelResult,
            ProjectionResult.Builder resultBuilder
    ) {
        // Create the manifest where plugin artifacts are stored.
        Path pluginBaseDir = baseProjectionDir.resolve(pluginName);
        FileManifest manifest = fileManifestFactory.apply(pluginBaseDir);

        // Find the desired plugin in the SPI found plugins.
        SmithyBuildPlugin resolved = pluginFactory.apply(pluginName).orElse(null);

        if (resolved == null) {
            LOGGER.info(() -> String.format(
                    "Unable to find a plugin for `%s` in the `%s` projection",
                    pluginName, projectionName));
        } else if (resolved.requiresValidModel() && modelResult.isBroken()) {
            LOGGER.fine(() -> String.format(
                    "Skipping `%s` plugin for `%s` projection because the model is broken",
                    pluginName, projectionName));
        } else {
            LOGGER.info(() -> String.format(
                    "Applying `%s` plugin to `%s` projection",
                    pluginName, projectionName));
            resolved.execute(PluginContext.builder()
                    .model(projectedModel)
                    .originalModel(resolvedModel)
                    .projection(projectionName, projection)
                    .events(modelResult.getValidationEvents())
                    .settings(pluginSettings)
                    .fileManifest(manifest)
                    .pluginClassLoader(pluginClassLoader)
                    .sources(sources)
                    .build());
            resultBuilder.addPluginManifest(pluginName, manifest);
        }
    }

    private Map<String, ObjectNode> resolvePlugins(ProjectionConfig projection) {
        Map<String, ObjectNode> result = new TreeMap<>(config.getPlugins());
        result.putAll(projection.getPlugins());
        return result;
    }

    // Creates pairs where the left value is the configuration arguments of the
    // transformer, and the right value is the instantiated transformer.
    private List<Pair<ObjectNode, ProjectionTransformer>> createTransformers(
            String projectionName,
            ProjectionConfig config
    ) {
        List<Pair<ObjectNode, ProjectionTransformer>> resolved = new ArrayList<>(config.getTransforms().size());

        for (TransformConfig transformConfig : config.getTransforms()) {
            String name = transformConfig.getName();

            if (name.equals("apply")) {
                resolved.add(createApplyTransformer(projectionName, transformConfig));
                continue;
            }

            ProjectionTransformer transformer = transformFactory.apply(name)
                    .orElseThrow(() -> new UnknownTransformException(String.format(
                            "Unable to find a transform for `%s` in the `%s` projection.", name, projectionName)));
            resolved.add(Pair.of(transformConfig.getArgs(), transformer));
        }

        return resolved;
    }

    // This is a somewhat hacky special case that allows the "apply" transform to apply
    // transformations defined on other projections.
    private Pair<ObjectNode, ProjectionTransformer> createApplyTransformer(
            String projectionName,
            TransformConfig transformConfig
    ) {
        Apply.ApplyCallback callback = (currentModel, projectionTarget, visited) -> {
            if (projectionTarget.equals(projectionName)) {
                throw new SmithyBuildException(
                        "Cannot recursively apply the same projection: " + projectionName);
            } else if (visited.contains(projectionTarget)) {
                visited.add(projectionTarget);
                throw new SmithyBuildException(String.format(
                        "Cycle found in apply transforms: %s -> ...", String.join(" -> ", visited)));
            } else if (!transformers.containsKey(projectionTarget)) {
                throw new UnknownProjectionException(String.format(
                        "Unable to find projection named `%s` referenced by `%s`",
                        projectionTarget, projectionName));
            }
            Set<String> updatedVisited = new LinkedHashSet<>(visited);
            updatedVisited.add(projectionTarget);
            return applyProjectionTransforms(currentModel, model, projectionTarget, updatedVisited);
        };

        return Pair.of(transformConfig.getArgs(), new Apply(callback));
    }
}
