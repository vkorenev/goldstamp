# GoldStamp

Snapshot testing for Java. Pass in a POJO or a JSON tree, such as a parsed REST response.
Rules you declare strip volatile parts, such as timestamps and IDs, and GoldStamp compares the
result with a snapshot file you approved by hand.

## Quick start

GoldStamp is not published yet. Inside this build, add `testImplementation(project(":jackson3-json"))`.

```java
var json = JacksonJson.defaults();
var snapshots = json.verifier()
    .with(
        json.remove("$.generatedAt"),
        json.replace("$.requestId", "<request-id>"),
        json.remove("$.debug").optional(),
        json.sort("$.items", "id"))
    .build();

snapshots.assertMatches("raw", json.parse(responseBody));  // a JsonNode

var orders = snapshots.prependTransformation(json.treeMapping());  // Verifier<Object>
orders.assertMatches("orders", order);                               // a POJO, record or Map
```

1. **First run.** The test fails and writes `build/snapshots/received/orders.received.json`.
2. **Approve.** Review that file, copy it to `src/test/snapshots/approved/orders.approved.json`
   and commit it. GoldStamp never writes approved files itself.
3. **Later runs.** A match passes. A difference fails with the first differing line and writes
   a fresh received file.

## Rules

| To | Write |
| --- | --- |
| Remove values | `json.remove("$.items[*].timestamp")` |
| Remove array elements | `json.remove("$.items[?(@.temporary == true)]")` |
| Replace values (any Java value) | `json.replace("$.token", "<token>")` |
| Compute a replacement | `json.transform("$.name", n -> json.toTree(n.asString().trim()))` |
| Sort an array | `json.sort("$.tags")` |
| Sort by keys | `json.sort("$.people", "name.last", "name.first")` |
| Sort with a comparator | `json.sort("$.items", Comparator.comparing(n -> n.get("name").asString()))` |
| Tolerate no match | `json.remove("$.debug").optional()` |

Rules run in order. A rule that matches nothing fails the test unless it is `.optional()`.

## More

- `verifier.prependTransformation(rule)` returns a copy that runs the rule first, so a shared
  verifier can be extended per test. Pass a `List` to `prependTransformations` for several.
- `verifier.prependTransformation(mapping)` also adapts the input type, e.g. to
  `Verifier<Order>`.
- `verify(id, input)` returns a `VerificationResult` instead of throwing.
- To change the JSON layout or naming, use
  `JacksonJson.of(JacksonJson.defaultMapper().rebuild()...build())`.
- `new TextFormat("md").verifier().build()` handles plain-text snapshots.

The guides cover the rest:

- [User guide](docs/guide.md) covers configuration, rule details, JsonPath limitations,
  comparison and errors.
- [Extending GoldStamp](docs/extending.md) covers custom formats, other JSON libraries,
  transformations and comparators.
- [Design](docs/design.md) explains how GoldStamp works and why.
- [Contributing](CONTRIBUTING.md) covers building and submitting changes.

## License

Licensed under either of [Apache License 2.0](LICENSE-APACHE) or [MIT](LICENSE-MIT), at your
option (`MIT OR Apache-2.0`).
