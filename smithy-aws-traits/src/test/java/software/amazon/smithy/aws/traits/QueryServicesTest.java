package software.amazon.smithy.aws.traits;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.PaginatedIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.utils.Pair;

public class QueryServicesTest {
    @Test
    public void foo() {
        getServices();
    }

    public Map<ServiceShape, Model> getServices() {
        return streamServices().collect(Collectors.toMap(Pair::getKey, Pair::getValue));
    }

    public Stream<Pair<ServiceShape, Model>> streamServices() {
        try {
            Path baseDir = Paths.get(getClass().getResource("services").toURI());
            return Files.list(baseDir)
                    .map(path -> Model.assembler()
                            .putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true)
                            .discoverModels()
                            .addImport(path)
                            .assemble().getResult().get())
                    .map(model -> Pair.of(model.shapes(ServiceShape.class).iterator().next(), model));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Pair<String, Set<String>> getPaginated(Model model) {
        PaginatedIndex index = PaginatedIndex.of(model);

        Set<String> paginatedOperations = new TreeSet<>();
        for (ServiceShape service : model.shapes(ServiceShape.class).collect(Collectors.toSet())) {
            TopDownIndex topDownIndex = TopDownIndex.of(model);
            topDownIndex.getContainedOperations(service).forEach(operation -> {
                index.getPaginationInfo(service, operation).ifPresent(paginationInfo -> {
                    paginatedOperations.add(operation.getId().getName());
                });
            });
            return Pair.of(service.expectTrait(ServiceTrait.class).getSdkId(), paginatedOperations);
        }
        return null;
    }
}
