package software.amazon.smithy.aws.traits;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.PaginatedIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.utils.Pair;

public class QueryPaginators {
    @Test
    public void queryPaginators() throws Exception {
        Path baseDir = Paths.get(getClass().getResource("services").toURI());
        ObjectNode stuff = Node.objectNode();
        for (Path path : Files.list(baseDir).collect(Collectors.toSet())) {
            Model model = Model.assembler()
                    .discoverModels()
                    .addImport(path)
                    .assemble()
                    .unwrap();
            Pair<String, Set<String>> result = getPaginated(model);
            if (result != null && !result.getRight().isEmpty()) {
                stuff = stuff.withMember(result.getLeft(), Node.fromStrings(result.getRight()));
            }
        }
        Path output = Paths.get("/Users/phjordon/tmp/smithy-paginators/smithy-stuff.json");
        Files.write(output, Node.prettyPrintJson(stuff).getBytes());
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
