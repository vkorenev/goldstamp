# Extending GoldStamp

The extension points live in `goldstamp.core.spi`. Their Javadoc is the full contract.
There is no registry: pass your implementations to `Verifier.builder(format)` and
the builder methods. Input types are adapted with `Verifier.prependTransformation`, not by formats.

## Custom transformations

A transformation is a `Mapping<T, T>`: `apply(document, canModifySource)` plus
`resultSharing()`. To run a function on a whole JSON document, use `json.transform("$", fn)`:

```java
var snapshots = json.verifier()
    .with(json.transform("$", doc -> ((ObjectNode) doc).put("version", "<version>")))
    .build();
```

| Create with | Argument | Result |
| --- | --- | --- |
| `Mapping.inPlace(fn, copier)` | May be modified; `copier` copies it first unless the caller handed it over. | `UNSHARED`. |
| `Mapping.shared(fn)` | Never modified. | `SHARED`: anything may reference it, so it is copied before an in-place step. |
| `Mapping.withSource(fn)` | Never modified. | `WITH_SOURCE`: the argument or a wrapper built from it, referenced by nothing else. |
| `Mapping.unshared(fn)` | Never modified. | `UNSHARED`: a new object. |

A `WITH_SOURCE` result may be modified exactly when the argument could be, so a chain of
rules and `derived` steps copies once. A `SHARED` result may be a constant or a cache entry,
so it is always copied before an in-place step. Declare `withSource` or `unshared` only when it is
true: if the function returns an object that something else references, that object will be
modified later.

A transformation may return a new root. Any nodes it adds must be safe for later
transformations to mutate. Transformations never see approved snapshots. To control copying yourself, as JSON
rules do, implement `apply(document, canModifySource)`: copy first when it is `false`, and
override `resultSharing()` truthfully (`SHARED` by default). The verifier tells each step
whether it may modify its argument, based on the previous step's sharing.

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
| `serialize(T)` | Returns the snapshot text. |
| `deserialize(String)` | Reads your own output and equivalent hand-edited text. |

For a minimal example, see
[`TextFormat`](../core/src/main/java/goldstamp/core/text/TextFormat.java). Implementations
shared across tests must be thread-safe.
