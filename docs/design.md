# Design

This page explains how GoldStamp is put together and why.

## Goals

GoldStamp helps you test code whose output is large or noisy, such as REST API responses.
You capture the output, strip the parts that change on every run, and compare the rest with
a snapshot that you approved by hand.

- **Easy first test.** Sensible defaults and a few lines of setup.
- **Every stage can be replaced.** You can swap input conversion, rules, serialization or
  comparison without fighting the defaults.
- **Small and general.** We prefer one general mechanism over many special cases.

## How a verification works

```
input ──transform──▶ document ──rules──▶ document ──compare──▶ approved file
                                                  └─ on mismatch: write received file
```

1. Your input, such as a POJO, is mapped to a document with `prependTransformation`. A verifier of
   documents, such as `JsonNode`, takes them directly.
2. **Transformations** (rules or functions) clean up that document in order. Each one
   copies the document before changing it unless the caller handed it over, and says whether its
   result may be shared, so a copy is made only when needed and your object is never touched.
3. A **comparator** checks the result against the approved file. By default it compares
   text.
4. If the snapshot is missing or different, GoldStamp writes a `*.received.*` file for you
   to review. It never writes approved files.

## Modules

| Module | What it gives you |
| --- | --- |
| `core` | The verifier, results, plain-text snapshots and the extension interfaces. It has no dependencies. |
| `jsonpath` | JsonPath rules for any JSON library. |
| `jackson3-json` | `JacksonJson`, the ready-to-use JSON format built on Jackson 3. |

A *format* (`SnapshotFormat<T>`) is the central idea. It owns everything specific to one
document model:
- the file extension;
- reading and writing snapshot text.

Each module's format class is also its entry point, so you rarely need anything else.
Adding Jackson 2, Gson or YAML means writing one new format class. See
[Extending GoldStamp](extending.md).
