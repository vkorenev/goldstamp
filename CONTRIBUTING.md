# Contributing

Thanks for helping! Skim [docs/design.md](docs/design.md) for the big picture first.

## Build

GoldStamp needs Java 17. CI runs the same build:

```sh
./gradlew build          # compile, test, check Javadoc and formatting
./gradlew spotlessApply  # fix formatting
```

## Tests

We like tests that read like usage examples. A good test:
- creates a verifier;
- runs it through missing, approve, match and different;
- shows the input JSON next to the expected output.

`SnapshotWorkflowTest` is the model to follow.

## Docs

Keep docs short and put each topic in one place:

| File | For |
| --- | --- |
| `README.md` | New users: quick start and a rules cheat sheet |
| `docs/guide.md` | Users: everything else about using GoldStamp |
| `docs/extending.md` | Writing your own formats, transformers and comparators |
| `docs/design.md` | How GoldStamp works and why |
