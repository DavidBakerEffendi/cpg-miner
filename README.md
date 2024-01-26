CPG Miner
=============================================

A program that makes use of Joern take a CPG and some target procedure and extract the code as well as
the sub-graphs of that procedure.

## Getting Started

To build and launch (with a specific Log4j2 config)

```
sbt stage
./cpg_miner -Dlog4j.configurationFile=log4j2.xml
```

### Usage

Generate a CPG using Joern, e.g. `joern-parse <input_dir> -o <output_cpg>`. Use this `<output_cpg>` as the input for
`cpg-miner`.

Alternatively, you can use `./cpg_miner from-code <input_dir>` and it will generate a CPG for you to ingest. This will 
be created at `<input_dir>/cpg.bin`.

```
Mines code snippets and sub-graphs from CPGs.
Usage: cpg_miner [from-graph|from-code] [options] <args>...

  --help
  -o, --output-dir <value>
                           The output directory to dump the mined artifacts.
  -m, --method-name <value>
                           The name of the target procedure to mine.
  -c, --show-callees       Attempts to resolve and dump the methods called by the target method. Default is false.
Command: from-graph input-cpg

  input-cpg                The input cpg to mine from.
Command: from-code input-dir

  input-dir                The input directory to generate a CPG from
```

**Note**: The `<output-dir>` is not cleaned before run, but existing results will be overwritten during successive runs.

## Output

The structure of the output directory is as follows:

```
- <method_name>
 | - ast.json       # Serialized AST
 | - cfg.json       # Serialized CFG
 | - code.<ext>     # Code snippet, where <ext> is the language file suffix
 | - ddg.json       # Serialized DDG
 | - pdg.json       # Serialized PDG (DDG + CDG)
```

If there are more than 1 matches for the method, then it will be suffixed by `_idx` where `idx` is a counter.

### Graph Schema

The JSON graphs follow the following schema.

#### Graph

```json
{
  "nodes": "<Node[]>",
  "edges": "<Edge[]>"
}
```

#### Node

```json
{
  "id": "<number>",
  "label": "<string>",
  "name": "<string>",
  "code": "<string>"
}
```

#### Edge

```json
{
  "src": "<number>",
  "dst": "<number>",
  "value": "<string>",
  "label": "<string>"
}
```
