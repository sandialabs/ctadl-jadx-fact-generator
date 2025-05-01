{
  jadxFactgen,
  python3,
  jre,
  makeWrapper,
  lib,
}: let
  withPythonWheel = final: prev: {
    outputs = prev.outputs ++ ["whl"];
    postInstall =
      prev.postInstall
      or ""
      + ''
        mkdir $whl
        cp -r dist/* $whl/
      '';
  };
  pkg = python3.pkgs.buildPythonPackage rec {
    pname = "ctadl-jadx-fact-generator-plugin";
    version = lib.strings.removeSuffix "\n" (builtins.readFile ./src/ctadl_jadx_fact_generator_plugin/VERSION);
    src = ./.;

    doCheck = false;

    buildInputs = [];
    propagatedBuildInputs = [jadxFactgen];

    postConfigure = ''
      cp ${jadxFactgen}/lib/*.jar src/ctadl_jadx_fact_generator_plugin/
    '';

    passthru = {
      makeWrapperArgs = [''--set JAVA_HOME ${jre.home}''];
    };
  };
in
  pkg.overrideAttrs withPythonWheel
