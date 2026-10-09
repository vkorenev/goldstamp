# GoldStamp

GoldStamp is a snapshot testing library for building test coverage around captured output,
such as REST API responses. You hand it a document, optionally apply rules that sanitize
volatile parts, and it compares the result with a manually approved snapshot file:

- **Missing or different:** the test fails and the actual output is written to a
  `*.received.<ext>` file for you to review.
- **Approved:** you copy the received file over the approved one. GoldStamp never writes
  approved files itself.
- **Matching:** the test passes and any stale received file is removed.

## License

This project is licensed under either of

- [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) ([`LICENSE-APACHE`](LICENSE-APACHE))
- [MIT license](https://opensource.org/licenses/MIT) ([`LICENSE-MIT`](LICENSE-MIT))

at your option.

The [SPDX](https://spdx.dev) license identifier for this project is `MIT OR Apache-2.0`.
