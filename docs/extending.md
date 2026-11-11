# Extending GoldStamp

The extension points live in `goldstamp.core.spi`. Their Javadoc is the full contract.
There is no registry: pass your implementations to `SnapshotVerifier.builder(format)` and
the builder methods.

## Custom transformers

`DocumentTransformer<T>` is a single method, `T apply(T)`, so a lambda works:

```java
var snapshots = json.verifier()
    .with(doc -> ((ObjectNode) doc).put("version", "<version>"))
    .build();
```

A transformer may mutate its input or return a new root. Any nodes it adds must be safe for
later transformers to mutate. Transformers never see approved snapshots.

## Custom comparators

- **`TextComparator`** compares the approved file's text with the serialized actual
  document. It is the default.
- **`DocumentComparator<T>`** compares the parsed approval with the actual document. For
  details, see [Comparison](guide.md#comparison).

Both return `ComparisonResult.matching()` or `ComparisonResult.different(explanation)`. They
must not mutate their operands.

## Another JSON library

Extend `goldstamp.jsonpath.JsonTreeFormat<T>`. Your subclass inherits the rules, `parse` and
`verifier()`, and supplies:

1. The JsonPath `JsonProvider` and `MappingProvider` for the library. Jayway JsonPath ships
   them for Jackson 2, Gson, json-smart and Jakarta JSON.
2. `toTree(Object)`, which deep-copies a tree node, converts any other value, and maps
   `null` to JSON null.
3. `extension()`, `serialize` and `deserialize`.

[`JacksonJson`](../jackson3-json/src/main/java/goldstamp/jackson3/json/JacksonJson.java) is
the reference implementation. Only the Jackson 3 provider is tested.

## Another format

Implement `SnapshotFormat<T>` directly:

| Method | Contract |
| --- | --- |
| `extension()` | A file suffix such as `yaml` or `tar.gz`, with no leading dot. It is validated when the verifier is built. |
| `toDocument(Object)` | Returns a fresh document that transformers may mutate. Throws `IllegalArgumentException` for unsupported input. |
| `serialize(T)` | Returns the snapshot text. |
| `deserialize(String)` | Reads your own output and equivalent hand-edited text. |

For a minimal example, see
[`TextFormat`](../core/src/main/java/goldstamp/core/text/TextFormat.java). Implementations
shared across tests must be thread-safe.
