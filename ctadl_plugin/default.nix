{
  jadxFactgen,
  python3,
  jre,
  makeWrapper,
  lib,
}:
python3.pkgs.buildPythonPackage rec {
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
}
