# User guide

This guide covers everything past the [README](../README.md) quick start. Examples use
`var json = JacksonJson.defaults();`.

- [Verifiers](#verifiers)
- [Input](#input)
- [Configuring Jackson](#configuring-jackson)
- [Rules](#rules)
- [JsonPath limitations](#jsonpath-limitations)
- [Comparison](#comparison)
- [Errors](#errors)

## Verifiers

```java
var base = json.verifier()
    .approvedDirectory(Path.of("src/test/snapshots/approved"))  // default
    .receivedDirectory(Path.of("build/snapshots/received"))     // default
    .with(json.remove("$.generatedAt"))
    .build();
var orders = base.with(json.sort("$.items", "id"));  // base is unchanged
```

- **Directories** are resolved against the working directory.
- **Snapshot IDs** become file names: `<id>.approved.json` and `<id>.received.json`. An ID
  is letters, digits, `.`, `_` and `-`, and must start with a letter or digit.
- **Concurrent tests** must use distinct IDs.
- **`assertMatches`** throws `AssertionError` on a missing or different snapshot.
- **`verify`** returns a `VerificationResult`, which is one of `Matching`, `Missing` or
  `Different`:
  - Every result has `matches()` and `diagnostic()`.
  - `Missing` and `Different` also have `receivedFile()`.
- **On a match**, any stale received file is deleted.

## Input

The format turns each input into a fresh document before any rule runs. Your object is
never modified.

| Input | Becomes |
| --- | --- |
| `JsonNode` | A deep copy. |
| POJO, record, `Map`, collection, number | The result of `mapper.valueToTree`, which honors `@JsonProperty`, `@JsonIgnore` and other Jackson annotations. |
| `String` (any `CharSequence`) or `byte[]` | Rejected with `IllegalArgumentException`, because it is probably unparsed JSON. Use `json.parse(text)` instead. |

## Configuring Jackson

One `JsonMapper` drives input conversion, snapshot text and rules. Start from the defaults:

```java
var json = JacksonJson.of(JacksonJson.defaultMapper().rebuild()
    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    .build());
```

The default mapper does the following:
- sorts object keys;
- rejects text after the JSON value;
- indents with two spaces, uses LF newlines and writes `"name": value`.

Snapshots end with a newline. A mapper built from scratch is used as is. Use rules only with
the format that created them, because a rule converts values with its own format's mapper.

## Rules

Rules are immutable `DocumentTransformer`s. They run in order, and each one sees the
previous rule's result.

**Matching**
- A rule needs at least one match unless it is `.optional()`. One match anywhere is
  enough: `$.items[*].id` passes even if only one item has an `id`.
- An optional rule still fails on a value it cannot handle, such as sorting a non-array.
- `replace`, `transform` and `sort` may target the root (`$`). `remove` may not.

**Replace and transform**
- `replace(selector, value)` converts the value with the mapper once and copies it at every
  match. `"<id>"` becomes a JSON string and `null` becomes JSON null.
- `transform(selector, fn)` passes each matched node to `fn` and uses its non-null result.
  `fn` may mutate the node and return it.

**Sort**
- Sort keys are definite paths relative to each element. `"id"`, `"$.id"` and `"@.id"` are
  equivalent.
- Wildcards, filters and functions are not allowed in keys.
- Every element must have every key.
- By default, values are compared with [`JsonOrdering`](../jsonpath/src/main/java/goldstamp/jsonpath/JsonOrdering.java):
  - types sort as null, false, true, numbers, strings, arrays, objects;
  - numbers compare numerically;
  - objects ignore key order.
- A custom comparator replaces `JsonOrdering` for the keys, e.g.
  `json.sort("$.items", json.ordering().reversed(), "id")`.
- Remaining ties are always broken by `JsonOrdering` on the whole element, so the result
  never depends on input order.

Rules also work without a verifier, mutating the tree you pass in:

```java
JsonNode clean = json.remove("$.debug").optional().apply(json.parse(text));
```

## JsonPath limitations

GoldStamp passes selectors to Jayway JsonPath without checking this subset. Selectors
outside it may run without an error, with undefined results.

- **Supported:** property paths, non-negative indexes (including lists such as
  `$.items[0,2]`), wildcards and filters, plus recursive selections whose matches don't
  overlap.
- **No nested matches in one rule.** For example, `$..*` over nested containers is not
  supported. Use separate rules instead.
- **No negative indexes or slice bounds.** A removal with them can silently do nothing.
- **Select each location once.** `$[1,1]` can remove two elements.
- **No property unions** such as `$['a','b']`. They can report a match for missing
  properties. Use two optional rules instead.
- **No precompiled selectors or bound predicates.** Selectors are strings; use inline
  filters such as `[?(@.temporary == true)]`.

Later rules see the modified document, including indexes that shifted.

## Comparison

By default, `LineComparator` compares the approved file's exact text with the serialized
actual document. It reports the first differing line and ignores CRLF versus LF. For a
richer diff, compare the received file with your own diff tool.

A hand-edited approval can fail on whitespace or key order alone. To compare parsed
documents instead, set a `DocumentComparator`:

```java
var snapshots = json.verifier()
    .documentComparator((approved, actual) -> approved.equals(actual)
        ? ComparisonResult.matching()
        : ComparisonResult.different("Documents differ; diff the received file"))
    .build();
```

A document comparator gets the actual document before serialization, so it must tolerate
representation changes: for example, a `LongNode(1)` reloads as an `IntNode(1)`. For
structural diffs, a JSON diff library can do the comparison; check its Jackson 3 support.

## Errors

| Problem | Exception | When it is thrown |
| --- | --- | --- |
| Invalid selector or sort key, function path, removing `$` | `IllegalArgumentException` | When the rule is created |
| Invalid snapshot ID, text or `byte[]` input | `IllegalArgumentException` | When `verify` runs |
| A rule cannot apply, e.g. `Rule remove($['debug']) matched no values` | `SnapshotException` naming the rule | When `verify` runs; no received file is written |
| A snapshot file cannot be read, written or deleted, or (with a document comparator) parsed | `SnapshotException` naming the file | When `verify` runs |
| `null` input, or a transformer or transform function returns `null` | `NullPointerException` | When `verify` runs |
| The mapper cannot convert your input, or your own code throws | Their exception, unchanged | When `verify` runs |
