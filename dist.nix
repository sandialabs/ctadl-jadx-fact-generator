{ stdenv
, python3
, jadxFactgenPlugin
, ensureNewerSourcesForZipFilesHook
, lib
}: stdenv.mkDerivation rec {
  pname = "ctadl-jadx-fact-generator-dist";
  inherit (jadxFactgenPlugin) version;

  src = ./ctadl_plugin;

  nativeBuildInputs = [ ensureNewerSourcesForZipFilesHook ];
  buildInputs = [ ];

  buildPhase = ''
  runHook preBuild

  cp ${jadxFactgenPlugin}/${python3.sitePackages}/ctadl_jadx_fact_generator_plugin/ctadl-jadx-fact-generator.jar src/ctadl_jadx_fact_generator_plugin/

  runHook postBuild
  '';

  installPhase = ''
  runHook preInstall

  mkdir -p $out
  tar --transform 's,^,ctadl-jadx-fact-generator-v${jadxFactgenPlugin.version}/,' -cvzf $out/ctadl-jadx-fact-generator-v${jadxFactgenPlugin.version}.tar.gz  *

  runHook postInstall
  '';
}
