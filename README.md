# CTADL JADX Fact Generator

This project is part of the [CTADL Taint Analyzer](https://github.com/sandialabs/ctadl).

The CTADL JADX Fact Generator is a java program that uses [jadx](https://github.com/skylot/jadx) as a library to generate a directory of "facts" -- tab-separated value files -- representing all the bytecode instructions and much of the metadata from a given Java binary (APK or jar). These facts are useful for doing Datalog-based program analysis on JADX programs.

Java 11 or newer is required to run.

# Installation

Use pip:

    $ pip install ctadl-jadx-fact-generator-plugin

Afterward, if ctadl is installed, you can do:

    $ ctadl import jadx /path/to/apk

See `ctadl --help` for more detauls.

# Building

Run `./gradlew distZip` then find the zipped output in `./build/distributions/`.
Run from the bin directory of the distZip.

# Running

You shouldn't need to run this directly unless you're developing it. The ctadl export plugin runs it for you.

Run the jar with arguments: `java -jar ctadl-jadx-fact-generator.jar /path/to/apk`

To see help, do `java -jar ctadl-jadx-fact-generator.jar --help`. When running, pass the file to
run on. The output facts will be in the `./output` directory or whatever
directory you pass as an option.

The fact-generator has two phases: (1) fact generation and (2) apk/jar rewriting.
Fact generation exports the program into tab-separated value files so that CTADL can analyze it.
You may see a lot of errors, specifically JadxExceptions.
Jadx exceptions are expected; it generates a lot of them and they seem to be benign.
This phase is terminate with a message that says `Output in <path>`.

The second phase, apk/jar rewriting, may also print some scary messages.
Note that even if this phase fails, the first phase likely generated proper, usable CTADL facts.
The success of the second phase only affects workflows that intend to use (i.e., debug) the rewritten app; but most workflows need just the facts.
