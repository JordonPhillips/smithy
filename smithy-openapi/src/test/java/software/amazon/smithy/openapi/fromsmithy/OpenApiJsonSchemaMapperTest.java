/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.openapi.fromsmithy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.jsonschema.JsonSchemaConfig;
import software.amazon.smithy.jsonschema.JsonSchemaConverter;
import software.amazon.smithy.jsonschema.Schema;
import software.amazon.smithy.jsonschema.SchemaDocument;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.ByteShape;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.IntegerShape;
import software.amazon.smithy.model.shapes.LongShape;
import software.amazon.smithy.model.shapes.ShortShape;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.DeprecatedTrait;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.DynamicTrait;
import software.amazon.smithy.model.traits.ExternalDocumentationTrait;
import software.amazon.smithy.model.traits.SensitiveTrait;
import software.amazon.smithy.model.traits.TraitDefinition;
import software.amazon.smithy.openapi.OpenApiConfig;
import software.amazon.smithy.openapi.traits.SpecificationExtensionTrait;
import software.amazon.smithy.utils.ListUtils;

public class OpenApiJsonSchemaMapperTest {
    @Test
    public void convertsModels() {
        Model model = Model.assembler()
                .addImport(getClass().getResource("test-service.json"))
                .discoverModels()
                .assemble()
                .unwrap();
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .config(new OpenApiConfig())
                .model(model)
                .build()
                .convert();

        assertTrue(document.toNode().expectObjectNode().getMember("components").isPresent());
    }

    @Test
    public void supportsExternalDocs() {
        String key = "Homepage";
        String link = "https://foo.com";
        StringShape shape = StringShape.builder()
                .id("a.b#C")
                .addTrait(ExternalDocumentationTrait.builder().addUrl(key, link).build())
                .build();
        Model model = Model.builder().addShape(shape).build();
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .config(new OpenApiConfig())
                .model(model)
                .build()
                .convertShape(shape);

        ObjectNode expectedDocs = ObjectNode.objectNodeBuilder()
                .withMember("description", key)
                .withMember("url", link)
                .build();

        Node.assertEquals(document.getRootSchema().getExtension("externalDocs").get(), expectedDocs);
    }

    @Test
    public void supportsCustomExternalDocNames() {
        String key = "CuStOm NaMe";
        String link = "https://foo.com";
        StringShape shape = StringShape.builder()
                .id("a.b#C")
                .addTrait(ExternalDocumentationTrait.builder().addUrl(key, link).build())
                .build();
        Model model = Model.builder().addShape(shape).build();
        OpenApiConfig config = new OpenApiConfig();
        config.setExternalDocs(ListUtils.of("Custom Name"));
        SchemaDocument document = JsonSchemaConverter.builder()
                .config(config)
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .build()
                .convertShape(shape);

        ObjectNode expectedDocs = ObjectNode.objectNodeBuilder()
                .withMember("description", key)
                .withMember("url", link)
                .build();
        Node.assertEquals(document.getRootSchema().getExtension("externalDocs").get(), expectedDocs);
    }

    @Test
    public void supportsDeprecatedTrait() {
        IntegerShape shape = IntegerShape.builder().id("a.b#C").addTrait(DeprecatedTrait.builder().build()).build();
        Model model = Model.builder().addShape(shape).build();
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .build()
                .convertShape(shape);

        assertThat(document.getRootSchema().getExtension("deprecated").get(), equalTo(Node.from(true)));
    }

    @Test
    public void appendsDeprecatedInfoInDescription() {
        String message = "Use a.b#D instead.";
        String since = "2020-01-01";
        IntegerShape shape = IntegerShape.builder()
                .id("a.b#C")
                .addTrait(DeprecatedTrait.builder().message(message).since(since).build())
                .addTrait(new DocumentationTrait("This is an integer."))
                .build();
        Model model = Model.builder().addShape(shape).build();
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .build()
                .convertShape(shape);

        String expected = "This is an integer.\nThis shape is deprecated since 2020-01-01: Use a.b#D instead.";
        assertThat(document.getRootSchema().getDescription().get(), equalTo(expected));
    }

    @Test
    public void disableDefaultDeprecatedMessage() {
        IntegerShape shape = IntegerShape.builder()
                .id("a.b#C")
                .addTrait(DeprecatedTrait.builder().build())
                .addTrait(new DocumentationTrait("This is an integer."))
                .build();
        Model model = Model.builder().addShape(shape).build();
        JsonSchemaConfig config = new JsonSchemaConfig();
        config.setDisableDefaultDeprecatedMessage(true);
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .config(config)
                .build()
                .convertShape(shape);

        String expected = "This is an integer.";
        assertThat(document.getRootSchema().getDescription().get(), equalTo(expected));

        // Asserts that deprecated node is still there
        assertThat(document.getRootSchema().getExtension("deprecated").get(), equalTo(Node.from(true)));
    }

    @Test
    public void disableDefaultDeprecatedMessageStillAllowsCustomMessage() {
        String message = "Use a.b#D instead.";
        IntegerShape shape = IntegerShape.builder()
                .id("a.b#C")
                .addTrait(DeprecatedTrait.builder().message(message).build())
                .addTrait(new DocumentationTrait("This is an integer."))
                .build();
        Model model = Model.builder().addShape(shape).build();
        JsonSchemaConfig config = new JsonSchemaConfig();
        config.setDisableDefaultDeprecatedMessage(true);
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .config(config)
                .build()
                .convertShape(shape);

        String expected = "This is an integer.\nThis shape is deprecated: Use a.b#D instead.";
        assertThat(document.getRootSchema().getDescription().get(), equalTo(expected));
    }

    @Test
    public void disableDefaultDeprecatedMessageStillAllowsSince() {
        String since = "2020-01-01";
        IntegerShape shape = IntegerShape.builder()
                .id("a.b#C")
                .addTrait(DeprecatedTrait.builder().since(since).build())
                .addTrait(new DocumentationTrait("This is an integer."))
                .build();
        Model model = Model.builder().addShape(shape).build();
        JsonSchemaConfig config = new JsonSchemaConfig();
        config.setDisableDefaultDeprecatedMessage(true);
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .config(config)
                .build()
                .convertShape(shape);

        String expected = "This is an integer.\nThis shape is deprecated since 2020-01-01.";
        assertThat(document.getRootSchema().getDescription().get(), equalTo(expected));
    }

    @Test
    public void disableDefaultDeprecatedMessageOnlyAffectsIfTrue() {
        IntegerShape shape = IntegerShape.builder()
                .id("a.b#C")
                .addTrait(DeprecatedTrait.builder().build())
                .addTrait(new DocumentationTrait("This is an integer."))
                .build();
        Model model = Model.builder().addShape(shape).build();
        JsonSchemaConfig config = new JsonSchemaConfig();
        config.setDisableDefaultDeprecatedMessage(false);
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .config(config)
                .build()
                .convertShape(shape);

        String expected = "This is an integer.\nThis shape is deprecated.";
        assertThat(document.getRootSchema().getDescription().get(), equalTo(expected));
    }

    @Test
    public void supportsInt32() {
        IntegerShape shape = IntegerShape.builder().id("a.b#C").build();
        Model model = Model.builder().addShape(shape).build();
        OpenApiConfig config = new OpenApiConfig();
        config.setUseIntegerType(true);
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .config(config)
                .model(model)
                .build()
                .convertShape(shape);

        assertThat(document.getRootSchema().getFormat().get(), equalTo("int32"));
    }

    @Test
    public void supportsInt64() {
        LongShape shape = LongShape.builder().id("a.b#C").build();
        Model model = Model.builder().addShape(shape).build();
        OpenApiConfig config = new OpenApiConfig();
        config.setUseIntegerType(true);
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .config(config)
                .model(model)
                .build()
                .convertShape(shape);

        assertThat(document.getRootSchema().getFormat().get(), equalTo("int64"));
    }

    @Test
    public void canDisableIntegerFormats() {
        IntegerShape integerShape = IntegerShape.builder().id("a.b#C").build();
        LongShape longShape = LongShape.builder().id("a.b#D").build();
        Model model = Model.builder().addShapes(integerShape, longShape).build();
        OpenApiConfig config = new OpenApiConfig();
        config.setUseIntegerType(true);
        config.setDisableIntegerFormat(true);
        JsonSchemaConverter converter = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .config(config)
                .model(model)
                .build();

        SchemaDocument integerDocument = converter.convertShape(integerShape);
        SchemaDocument longDocument = converter.convertShape(longShape);

        assertThat(integerDocument.getRootSchema().getFormat().isPresent(), equalTo(false));
        assertThat(integerDocument.getRootSchema().getType().get(), equalTo("integer"));
        assertThat(longDocument.getRootSchema().getFormat().isPresent(), equalTo(false));
        assertThat(longDocument.getRootSchema().getType().get(), equalTo("integer"));
    }

    @Test
    public void supportsFloatFormat() {
        FloatShape shape = FloatShape.builder().id("a.b#C").build();
        Model model = Model.builder().addShape(shape).build();
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .build()
                .convertShape(shape);

        assertThat(document.getRootSchema().getFormat().get(), equalTo("float"));
    }

    @Test
    public void supportsNonNumericFloatFormat() {
        FloatShape shape = FloatShape.builder().id("a.b#C").build();
        Model model = Model.builder().addShape(shape).build();
        JsonSchemaConfig config = new JsonSchemaConfig();
        config.setSupportNonNumericFloats(true);
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .config(config)
                .model(model)
                .build()
                .convertShape(shape);

        Schema rootSchema = document.getRootSchema();
        assertFalse(rootSchema.getFormat().isPresent());
        Schema numericSchema = null;
        for (Schema schema : rootSchema.getOneOf()) {
            if (schema.getType().isPresent() && schema.getType().get().equals("number")) {
                numericSchema = schema;
                break;
            }
        }
        assertNotNull(numericSchema);
        assertThat(numericSchema.getFormat().get(), equalTo("float"));
    }

    @Test
    public void supportsDoubleFormat() {
        DoubleShape shape = DoubleShape.builder().id("a.b#C").build();
        Model model = Model.builder().addShape(shape).build();
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .build()
                .convertShape(shape);

        assertThat(document.getRootSchema().getFormat().get(), equalTo("double"));
    }

    @Test
    public void supportsNonNumericDoubleFormat() {
        DoubleShape shape = DoubleShape.builder().id("a.b#C").build();
        Model model = Model.builder().addShape(shape).build();
        JsonSchemaConfig config = new JsonSchemaConfig();
        config.setSupportNonNumericFloats(true);
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .config(config)
                .model(model)
                .build()
                .convertShape(shape);

        Schema rootSchema = document.getRootSchema();
        assertFalse(rootSchema.getFormat().isPresent());
        Schema numericSchema = null;
        for (Schema schema : rootSchema.getOneOf()) {
            if (schema.getType().isPresent() && schema.getType().get().equals("number")) {
                numericSchema = schema;
                break;
            }
        }
        assertNotNull(numericSchema);
        assertThat(numericSchema.getFormat().get(), equalTo("double"));
    }

    @Test
    public void blobFormatDefaultsToByte() {
        BlobShape shape = BlobShape.builder().id("a.b#C").build();
        Model model = Model.builder().addShape(shape).build();
        SchemaDocument document = JsonSchemaConverter.builder()
                .config(new OpenApiConfig())
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .build()
                .convertShape(shape);

        assertThat(document.getRootSchema().getFormat().get(), equalTo("byte"));
    }

    @Test
    public void blobFormatOverriddenToBinary() {
        BlobShape shape = BlobShape.builder().id("a.b#C").build();
        Model model = Model.builder().addShape(shape).build();
        OpenApiConfig config = new OpenApiConfig();
        config.setDefaultBlobFormat("binary");
        SchemaDocument document = JsonSchemaConverter.builder()
                .config(config)
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .build()
                .convertShape(shape);

        assertThat(document.getRootSchema().getFormat().get(), equalTo("binary"));
    }

    @Test
    public void integerTypesDefaultsToNumber() {
        ByteShape byteShape = ByteShape.builder().id("a.b#Byte").build();
        ShortShape shortShape = ShortShape.builder().id("a.b#Short").build();
        IntegerShape integerShape = IntegerShape.builder().id("a.b#Integer").build();
        LongShape longShape = LongShape.builder().id("a.b#Long").build();
        StructureShape structureShape = StructureShape.builder()
                .id("a.b#Structure")
                .addMember("byte", byteShape.getId())
                .addMember("short", shortShape.getId())
                .addMember("int", integerShape.getId())
                .addMember("long", longShape.getId())
                .build();

        Model model = Model.builder()
                .addShapes(byteShape, shortShape, integerShape, longShape, structureShape)
                .build();
        SchemaDocument document = JsonSchemaConverter.builder()
                .config(new OpenApiConfig())
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .build()
                .convertShape(structureShape);

        assertThat(document.getRootSchema().getType().get(), equalTo("object"));
        assertThat(document.getRootSchema().getProperties().size(), equalTo(4));

        Map<String, Schema> properties = document.getRootSchema().getProperties();
        for (Map.Entry<String, Schema> property : properties.entrySet()) {
            assertThat(property.getValue().getType().get(), equalTo("number"));
        }
    }

    @Test
    public void integerTypesOverriddenToInteger() {
        ByteShape byteShape = ByteShape.builder().id("a.b#Byte").build();
        ShortShape shortShape = ShortShape.builder().id("a.b#Short").build();
        IntegerShape integerShape = IntegerShape.builder().id("a.b#Integer").build();
        LongShape longShape = LongShape.builder().id("a.b#Long").build();
        StructureShape structureShape = StructureShape.builder()
                .id("a.b#Structure")
                .addMember("byte", byteShape.getId())
                .addMember("short", shortShape.getId())
                .addMember("int", integerShape.getId())
                .addMember("long", longShape.getId())
                .build();

        Model model = Model.builder()
                .addShapes(byteShape, shortShape, integerShape, longShape, structureShape)
                .build();
        OpenApiConfig config = new OpenApiConfig();
        config.setUseIntegerType(true);
        SchemaDocument document = JsonSchemaConverter.builder()
                .config(config)
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .build()
                .convertShape(structureShape);

        assertThat(document.getRootSchema().getType().get(), equalTo("object"));
        assertThat(document.getRootSchema().getProperties().size(), equalTo(4));

        Map<String, Schema> properties = document.getRootSchema().getProperties();
        for (Map.Entry<String, Schema> property : properties.entrySet()) {
            assertThat(property.getValue().getType().get(), equalTo("integer"));
        }
    }

    @Test
    public void supportsSensitiveTrait() {
        StringShape shape = StringShape.builder().id("a.b#C").addTrait(new SensitiveTrait()).build();
        Model model = Model.builder().addShape(shape).build();
        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .build()
                .convertShape(shape);

        assertThat(document.getRootSchema().getFormat().get(), equalTo("password"));
    }

    @Test
    public void supportsSpecificationExtensionTrait() {
        StringShape extensionTraitShape = StringShape.builder()
                .id("a.b#extensionTrait")
                .addTrait(TraitDefinition.builder().build())
                .addTrait(SpecificationExtensionTrait.builder().as("x-important-metadata").build())
                .build();
        DynamicTrait extensionTraitInstance =
                new DynamicTrait(extensionTraitShape.getId(), StringNode.from("string content"));
        IntegerShape integerShape = IntegerShape.builder().id("a.b#Integer").build();
        StructureShape structure = StructureShape.builder()
                .id("a.b#Struct")
                .addTrait(extensionTraitInstance)
                .addMember("c", integerShape.getId())
                .build();

        Model model = Model.builder().addShapes(extensionTraitShape, integerShape, structure).build();

        SchemaDocument document = JsonSchemaConverter.builder()
                .addMapper(new OpenApiJsonSchemaMapper())
                .model(model)
                .build()
                .convertShape(structure);

        assertThat(
                document.getRootSchema()
                        .getExtension("x-important-metadata")
                        .get()
                        .toNode()
                        .expectStringNode()
                        .getValue(),
                equalTo("string content"));
    }
}
