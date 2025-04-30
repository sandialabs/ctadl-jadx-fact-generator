# CTADL JADX Fact Generator Plugin

This project is part of the [CTADL Taint Analyzer](https://github.com/sandialabs/ctadl).

This project provides a plugin for CTADL so that it can perform taint analysis on Java bytecode.
It is based on [jadx](https://github.com/skylot/jadx) code analysis.

# Installation

Use pip.

    $ pip install ctadl-jadx-fact-generator

Afterward, if ctadl is installed, you can do:

    $ ctadl import jadx /path/to/apk

See `ctadl --help` for more detauls.

Make sure, when you run ctadl, that `JAVA_HOME` is properly set up in your environment or Java is in your path.
If you forget, it'll remind you.
