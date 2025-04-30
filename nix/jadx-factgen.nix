{ stdenv
, fetchurl
, jadx
, jdk
, dex2jar
, apktool-lib
, makeWrapper
, python3
, merjar
, lib
}:
let
  version = lib.strings.removeSuffix "\n" (builtins.readFile ../ctadl_plugin/src/ctadl_jadx_fact_generator_plugin/VERSION);
  bin-name = "ctadl-jadx-fact-generator";
  jar-name = "${bin-name}.jar";
  json-simple = fetchurl {
    url = "https://repo1.maven.org/maven2/com/googlecode/json-simple/json-simple/1.1.1/json-simple-1.1.1.jar";
    hash = "sha256-TmlpaJK4i0HFXUmrL9zCHurZK/VKzFiMAFBZbDt1GZw=";
  };
in stdenv.mkDerivation {
  pname = "ctadl-jadx-fact-generator";
  inherit version;
  src = with builtins;
  builtins.path {
    path = ./..;
    name = "ctadl-jadx-fact-generator";
    filter = path: type: let
      ignoreFiles = ["release.sh"];
      ignoreDirs = ["ctadl_plugin"];
    in
    if
    (lib.lists.any (p: (builtins.match (".*" + p) path != null)) ignoreFiles)
    || (lib.lists.any (p: p == (baseNameOf path)) ignoreDirs)
    then false
    else true;
  };

  nativeBuildInputs = [ makeWrapper python3 ];
  buildInputs = [ jadx jdk dex2jar ];

  configurePhase = ''
    find src -type f -iname '*.java' > sources.txt
    find ${jadx}/lib -iname '*.jar' > jars.txt
    echo ${json-simple} >> jars.txt
    find ${dex2jar} -iname 'dex-writer*.jar' -type f >> jars.txt
    find ${dex2jar} -iname 'dex-reader*.jar' -type f >> jars.txt
    find ${apktool-lib} -iname '*.jar' -type f >> jars.txt
    cat jars.txt | \
      awk 'NR==1 { s = $0 } NR>1 { s = s ":" $0 } END { print s }' > classpath.txt
  '';

  buildPhase = ''
    javac -d . -cp lib/asm-tree-9.2.jar:$(cat classpath.txt) @sources.txt
  '';

  installPhase = ''
    find . -iname '*.class' > classes.txt
    jar -c -v --file app.jar @classes.txt
    echo 'lib/asm-tree-9.2.jar' >> jars.txt
    # Creates fat jar
    # Merges the services together
    ${merjar}/bin/merjar -o ${jar-name} --merge META-INF/services/jadx.api.plugins.JadxPlugin -i jars.txt app.jar
    jar --file ${jar-name} -u --main-class jadx_factgen.App
    
    mkdir -p $out/lib $out/share $out/bin
    cp ${jar-name} $out/lib
    # Copies libs
    #cp lib/asm-tree-9.2.jar $out/lib
    #find ${jadx}/lib -iname '*.jar' -execdir cp {} $out/lib \;
    #find ${dex2jar} -iname '*.jar' -execdir cp {} $out/lib \;
    #find ${apktool-lib} -iname '*.jar' -execdir cp {} $out/lib \;
    #find $out/lib -iname '*.jar' | \
      #awk 'NR==1 { s = $0 } NR>1 { s = s ":" $0 } END { print s }' > $out/share/classpath.txt
    echo '#!/bin/bash' >> $out/bin/${bin-name}
    echo 'java -cp '$out'/lib/${jar-name} jadx_factgen.App "$@"' >> $out/bin/${bin-name}
    chmod +x $out/bin/${bin-name}
    wrapProgram $out/bin/${bin-name} \
      --prefix PATH : ${lib.makeBinPath [ jdk ]}
  '';
}
