# Input limits of the loaders

Every loader that reads a file or stream someone else produced, and what it
does with large or hostile input. "No cap" means the whole input is held in
memory: size the heap to the file, or split the file first. All XML readers
refuse DTDs and external entities (no XXE, no entity expansion).

| Loader | Format | Limits | On breach |
|---|---|---|---|
| `CfiCodeRegistry.fromSixXlsx` (lowlatency) | SIX ISO 10962 .xlsx (zip of XML) | at most 10 000 zip entries; 64 MiB uncompressed per part read; 256 MiB for all parts read; only `xl/*.xml` and `.rels` parts are inflated | `IOException` before more is inflated |
| `FirdsInstrumentRegistry.load` (lowlatency) | ESMA FIRDS FULINS XML | streamed (StAX); no size cap; registry grows with the instruments read | heap |
| `GleifGoldenCopyLeiRegistry.load` (lowlatency) | GLEIF golden copy CSV | streamed line by line; no size cap; one entry per LEI kept | heap |
| `Iso10383MicRegistry.load` (lowlatency) | ISO 10383 MIC CSV | read line by line; no size cap; one entry per MIC kept (a few thousand) | heap |
| `StatusAdvice.parse` (lowlatency) | ESMA auth.031 XML | streamed (StAX); no size cap | heap |
| `CsvBarLoader.load` (core) | OHLCV CSV | `Files.readAllLines`: whole file in memory | heap |
| `UniverseCsvLoader.load` (core) | point-in-time universe CSV | `Files.readAllLines`: whole file in memory | heap |
| `Checkpoint.reader` (core) | own binary checkpoint | `Files.readAllBytes`: whole file in memory; array lengths are read from the file, so only load checkpoints you wrote | heap / `IOException` on a short file |
| FIX decoder (lowlatency) | FIX tag=value | works on the caller's buffer; no allocation per message | parse error |

Plain idea: the zip reader is the one place where a small input can expand to a
large one (a "zip bomb"), so it is the one with hard caps. The others grow
linearly with the file and are bounded by the heap you give them.

Worked example: the SIX workbook of 2021-05-07 has 319 entries and 1.5 MB
uncompressed, well inside the caps; a crafted zip with 10 001 one-byte entries
fails at the 10 001st entry (`CfiXlsxLimitsTest`).
