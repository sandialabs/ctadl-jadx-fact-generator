import subprocess
import importlib.metadata
import logging
import os
import json
import contextlib
import typing
import csv
import sys
import importlib.resources as resources
import shutil

from pathlib import Path


def read_version():
    filepath = str(resources.files(__name__) / "VERSION")
    with open(filepath) as file:
        for line in file:
            line = line.strip()
            return line.strip(' "')
    return "unknown-dev"


logger = logging.getLogger(__name__)

language = "JADX"
__version__ = version = read_version()
factgen = str(resources.files(__name__) / "ctadl-jadx-fact-generator.jar")


def make_parser(parser):
    parser.add_argument(
        "--jadx-rewrite-debug-info",
        action="store_true",
        help="Rewrite debug info inside APK/JAR to correspond to line numbers in decompilation. Necessary if using JADX GUI",
    )
    return parser


def run(ctadl, args, artifact: str, out: str, **kwargs):
    ctadl.status(f"ctadl_jadx_fact_generator_plugin {__version__}")
    logger.debug("artifact: %s", artifact)
    logger.debug("out: %s", out)

    command = export_command(ctadl)
    rewrite = getattr(args, "jadx_rewrite_debug_info")
    if rewrite is True:
        command.append("--rewrite-debug-info")
    command.extend(
        [
            "--output",
            out,
            artifact,
        ]
    )
    command.extend(kwargs.get("argument_passthrough", ["--suppress-jadx-exceptions"]))
    print("fact generator command:", " ".join(f"'{e}'" for e in command))
    logger.debug("jadx command: %s", command)
    res = subprocess.run(command)
    print("processing source maps...", file=sys.stderr)
    with SourceMapFiles().writer(out) as files:
        for smap_file in find_files_with_extension(
            os.path.join(out, "sources"), ".json"
        ):
            with open(smap_file, "r") as fp:
                # Could check that the first element is of a record and is
                # "version"
                smap = json.loads(fp.read())
                if "version" in smap:
                    source_map_to_facts(files, smap)
    return res


def export_command(ctadl) -> list[str]:
    java_home = os.getenv("JAVA_HOME")

    java_cmd = None
    if java_home:
        java_cmd = os.path.join(java_home, "bin", "java")
    else:
        java_cmd = shutil.which("java")

    if not java_cmd or not os.path.isfile(java_cmd) or not os.access(java_cmd, os.X_OK):
        ctadl.error(f"Java executable not found at {java_cmd}")
        ctadl.error(
            f"Either set JAVA_HOME so that $JAVA_HOME/bin/java is executable"
            " or put java in your PATH"
        )
        sys.exit(1)

    if not os.path.exists(factgen):
        ctadl.error(f"CTADL-JADX-FACT-GENERATOR jar is missing")
        ctadl.error(f"Please re-install CTADL-JADX-FACT-GENERATOR")
        ctadl.error(f"Putative path: {factgen}")

    return [java_cmd, "-jar", factgen]


def find_files_with_extension(directory, extension):
    for root, dirs, files in os.walk(directory):
        for file in files:
            if file.endswith(extension):
                yield os.path.join(root, file)


class CsvWriterType(typing.Protocol):
    def writerow(self, row: typing.Iterable[typing.Any]) -> typing.Any:
        pass


class SourceMapFiles:
    line_writer: CsvWriterType
    char_writer: CsvWriterType
    map_writer: CsvWriterType
    source_info_map: "SourceInfoMap"

    def __init__(self):
        self.source_info_map = SourceInfoMap()

    @contextlib.contextmanager
    def writer(self, out):
        stack = contextlib.ExitStack()
        # num, startLine, ...
        line_region = stack.enter_context(
            open(Path(out) / "facts" / "SARIFLineRegion.facts", mode="w", newline="")
        )
        # num, charOffset, charLength
        char_region = stack.enter_context(
            open(Path(out) / "facts" / "SARIFCharRegion.facts", mode="w", newline="")
        )
        # num, byteOffset, byteLength
        byte_region = stack.enter_context(
            open(Path(out) / "facts" / "SARIFByteRegion.facts", mode="w", newline="")
        )
        # num, uri, uriBaseId
        artifact = stack.enter_context(
            open(
                Path(out) / "facts" / "SARIFArtifactLocation.facts",
                mode="w",
                newline="",
            )
        )
        # binary artifact-num, binary region-num, decomp artifact-num, decomp region-num
        source_map = stack.enter_context(
            open(
                Path(out) / "facts" / "DecompilerSourceMap.facts", mode="w", newline=""
            )
        )
        self.line_writer = csv.writer(line_region, delimiter="\t")
        self.byte_writer = csv.writer(byte_region, delimiter="\t")
        self.char_writer = csv.writer(char_region, delimiter="\t")
        self.artifact_writer = csv.writer(artifact, delimiter="\t")
        self.map_writer = csv.writer(source_map, delimiter="\t")

        try:
            yield self
        finally:
            stack.__exit__(None, None, None)


class SourceInfoMap:
    def __init__(self):
        self.d = {}
        self.counter = 0

    def get_index(self, obj: typing.Any) -> tuple[int, bool]:
        hit = True
        sort_json_fields(obj)
        key = str(obj)
        if key not in self.d:
            self.d[key] = len(self.d)
            hit = False
        # print('obj:', obj, 'key:', key, 'hit:', hit)
        return self.d[key], hit

    def increment(self) -> int:
        n = self.counter
        self.counter += 1
        return n


def source_map_to_facts(files, smap):
    # print("source_map_to_facts", smap)
    for mapping in smap["mappings"]:
        binaryLocs = mapping.get("binary", [])
        decompLocs = mapping.get("source", [])
        binary_phy = None
        decomp_phy = None
        for loc in binaryLocs:
            if "physicalLocation" in loc:
                binary_phy = loc.get("physicalLocation")
                break
        for loc in decompLocs:
            if "physicalLocation" in loc:
                decomp_phy = loc.get("physicalLocation")
                break
        if binary_phy is None:
            print(
                "error: could not find physicalLocation in mapping:",
                mapping,
                file=sys.stderr,
            )
            exit(1)
        if decomp_phy is None:
            print(
                "error: could not find physicalLocation in mapping:",
                mapping,
                file=sys.stderr,
            )
            exit(1)
        binary_artifact_id = output_artifact_location(
            files, binary_phy["artifactLocation"], "BINROOT"
        )
        binary_region_id = output_region(files, binary_phy["region"])
        decomp_artifact_id = output_artifact_location(
            files, decomp_phy["artifactLocation"], "SRCROOT"
        )
        decomp_region_id = output_region(files, decomp_phy["region"])
        # outputs association between binary and decompiled source
        files.map_writer.writerow(
            [
                binary_artifact_id,
                binary_region_id,
                decomp_artifact_id,
                decomp_region_id,
            ]
        )


def output_artifact_location(files, loc, default_uri_base_id) -> int:
    loc_index, hit = files.source_info_map.get_index(loc)
    # print(loc, loc_index, hit)
    if hit:
        return loc_index
    files.artifact_writer.writerow(
        [
            loc_index,
            loc.get("uri"),
            loc.get("uriBaseId", default_uri_base_id),
        ]
    )
    return loc_index


def output_region(files, region) -> int:
    n, hit = files.source_info_map.get_index(region)
    if hit:
        return n
    if "byteOffset" in region:
        files.byte_writer.writerow(
            [n, region["byteOffset"], region.get("byteLength", -1)]
        )
    if "charOffset" in region:
        files.char_writer.writerow(
            [n, region["charOffset"], region.get("charLength", -1)]
        )
    if "startLine" in region:
        files.line_writer.writerow(
            [
                n,
                region["startLine"],
                region.get("startColumn", -1),
                region.get("endLine", -1),
                region.get("endColumn", -1),
            ]
        )
    return n


def sort_json_fields(obj) -> None:
    worklist = [obj]

    while worklist:
        current = worklist.pop()

        if isinstance(current, dict):
            sorted_dict = {key: current[key] for key in sorted(current.keys())}
            current.clear()
            current.update(sorted_dict)
            worklist.extend(sorted_dict.values())

        elif isinstance(current, list):
            sorted_list = [x for x in sorted(current)]
            current.clear()
            current.extend(sorted_list)
            worklist.extend(sorted_list)


def _print_info():
    print(f"{__version__}")
    print(f"{factgen}")
