RV32 Project implemented using Chisel
=======================

### Dependencies
#### JDK 11 or newer

We recommend using Java 11 or later LTS releases. While Chisel itself works with Java 8, our preferred build tool Mill requires Java 11. You can install the JDK as your operating system recommends, or use the prebuilt binaries from [Adoptium](https://adoptium.net/) (formerly AdoptOpenJDK).

#### SBT or mill

SBT is the most common build tool in the Scala community. You can download it [here](https://www.scala-sbt.org/download.html).  
mill is another Scala/Java build tool without obscure DSL like SBT. You can download it [here](https://github.com/com-lihaoyi/mill/releases)

By default, Mill will be automatically downloaded to local directory on first run, human intervention not required.

#### Verilator

The test with `svsim` needs Verilator installed.
See Verilator installation instructions [here](https://verilator.org/guide/latest/install.html).

#### Justfile

For the convenience of cross-platform, we are using justfile in place of makefile. For installation for various platforms (Linux/MacOS/Windows), see [here](https://github.com/casey/just). It is recommended to use it as as makefile is not readily available on windows.

#### Typst

For compilation of spec file written in typst.

### Performing tests
```sh
just test
```

### Generating Spec File
Generation will be done with github actions. But you can do it locally on your machine by:
```sh
typst compile spec.typ
```
or
```sh
just spec
```

### Project Directory Structure
| Path | Remark |
|------|--------|
| [src/](./src)   |   Chisel source code |
| [doc/spec.typ](./doc/spec.typ)   |    Specifications for the implementation of our RV32   |
| [doc/spec.pdf](./doc/spec.pdf)   |    Generated specifications for the implementation of our RV32   |
| [justfile](./justfile) | Similar to that of Makefile, provides ease of access to commonly used commands |

### Remark
+ justfile: Currently on linux, mill is automatically fetched when test is run.

### Work Distribution

| Part | Member |
|------|--------|
|组长| 刘恒雨|
|||
|||
|||
