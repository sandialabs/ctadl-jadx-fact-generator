#!/usr/bin/env python

import os
import shutil

from setuptools import setup
from setuptools.command.install import install as _install


jars = [
    (
        "src/ctadl_jadx_fact_generator_plugin/ctadl-jadx-fact-generator.jar",
        "ctadl-jadx-fact-generator.jar",
    )
]


class CustomInstall(_install):
    def run(self):
        # Runs the standard install command
        _install.run(self)
        self.copy_jar()

    def copy_jar(self):
        for src, dst in jars:
            srcfile = src
            dstfile = os.path.join(
                self.install_lib or "", f"ctadl_jadx_fact_generator_plugin/{dst}"
            )
            os.makedirs(os.path.dirname(dstfile), exist_ok=True)
            shutil.copy2(srcfile, dstfile)
            print(f"Copied {srcfile} to {dstfile}")


setup(
    cmdclass={
        "install": CustomInstall,
    },
    # other setup arguments...
)
