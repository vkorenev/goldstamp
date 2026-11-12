# Extending GoldStamp

The extension points live in `goldstamp.core.spi`. Their Javadoc is the full contract.
There is no registry: pass your implementations to `SnapshotVerifier.builder(format)` and
the builder methods. Input types are adapted with `Verifier.contramap`, not by formats.

## Custom transformers

`DocumentTransformer<T>` is a single method, `T apply(T)`, so a lambda works:

```java
var snapshots = json.verifier()
    .with(doc -> ((ObjectNode) doc).put("version", "<version>"))
    .build();
```

A transformer may mutate its input or return a new root. Any nodes it adds must be safe for
later transformers to mutate. Transformers never see approved snapshots.

The verifier copies the document before an in-place transformer receives one that is not yet
private. Lambdas and rules are in place. To let the verifier skip copies, declare the effect:

| Create with | Promise | Result |
| --- | --- | --- |
| `DocumentTransformer.pure(fn)` | Never mutates its argument. | May be shared; copied before the next in-place transformer. |
| `DocumentTransformer.fresh(fn)` | Never mutates its argument. | A new object, safe to mutate; not copied. |

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
3. `extension()`, `copy`, `serialize` and `deserialize`.

[`JacksonJson`](../jackson3-json/src/main/java/goldstamp/jackson3/json/JacksonJson.java) is
the reference implementation. Only the Jackson 3 provider is tested.

## Another format

Implement `SnapshotFormat<T>` directly:

| Method | Contract |
| --- | --- |
| `extension()` | A file suffix such as `yaml` or `tar.gz`, with no leading dot. It is validated when the verifier is built. |
| `copy(T)` | Returns a copy that transformers may mutate; an immutable document may be returned as is. |
| `serialize(T)` | Returns the snapshot text. |
| `deserialize(String)` | Reads your own output and equivalent hand-edited text. |

For a minimal example, see
[`TextFormat`](../core/src/main/java/goldstamp/core/text/TextFormat.java). Implementations
shared across tests must be thread-safe.
