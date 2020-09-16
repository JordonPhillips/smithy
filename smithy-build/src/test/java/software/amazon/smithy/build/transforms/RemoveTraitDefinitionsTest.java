/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.build.transforms;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.TransformContext;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;

public class RemoveTraitDefinitionsTest {

    @Test
    public void removesAllTraitDefinitions() throws Exception {
        Model model = Model.assembler()
                .addImport(Paths.get(getClass().getResource("remove-trait-definitions.json").toURI()))
                .assemble()
                .unwrap();
        TransformContext context = TransformContext.builder()
                .model(model)
                .settings(Node.objectNode().withMember("exportTagged", Node.arrayNode()))
                .build();
        Model result = new RemoveTraitDefinitions().transform(context);
        Shape baz = result.getShape(ShapeId.from("ns.foo#baz")).get();

        // Trait definitions are removed.
        assertFalse(result.getTraitDefinition(ShapeId.from("ns.foo#bar")).isPresent());
        assertFalse(result.getShape(ShapeId.from("ns.foo#bar")).isPresent());
        // Instance of trait on shape is retained.
        assertTrue(baz.hasTrait("ns.foo#bar"));
    }

    @Test
    public void retainsTaggedTraitDefinitions() throws Exception {
        Model model = Model.assembler()
                .addImport(Paths.get(getClass().getResource("retain-tagged-trait-definitions.json").toURI()))
                .assemble()
                .unwrap();
        TransformContext context = TransformContext.builder()
                .model(model)
                .settings(Node.objectNode().withMember("exportTagged", Node.fromStrings("export")))
                .build();
        Model result = new RemoveTraitDefinitions().transform(context);
        Shape baz = result.getShape(ShapeId.from("ns.foo#baz")).get();
        Shape garply = result.getShape(ShapeId.from("ns.foo#garply")).get();

        // Definition for trait "export" tag should be retained.
        assertTrue(result.getTraitDefinition(ShapeId.from("ns.foo#bar")).isPresent());
        assertTrue(result.getShape(ShapeId.from("ns.foo#bar")).isPresent());
        // Instance of trait on shape is retained.
        assertTrue(baz.hasTrait("ns.foo#bar"));

        // Definition for trait without "export" tag should be removed.
        assertFalse(result.getTraitDefinition(ShapeId.from("ns.foo#qux")).isPresent());
        assertFalse(result.getShape(ShapeId.from("ns.foo#qux")).isPresent());
        // Instance of trait on shape is retained.
        assertTrue(garply.hasTrait("ns.foo#qux"));
    }
}
