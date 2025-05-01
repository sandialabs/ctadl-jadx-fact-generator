{
  stdenv,
  lib,
}:
stdenv.mkDerivation rec {
  name = "apktool-lib";
  version = "2.9.3";
  src = builtins.fetchurl {
    url = "https://repo1.maven.org/maven2/org/apktool/apktool-lib/${version}/apktool-lib-${version}.jar";
    sha256 = "1w2wxxls6w1qjwvxz6aa8v841hzhl8a051i2hhgb3kk6dnjsg7pl";
  };
  src2 = builtins.fetchurl {
    url = "https://repo1.maven.org/maven2/org/apktool/brut.j.dir/${version}/brut.j.dir-${version}.jar";
    sha256 = "0y14wfvvh31wrckc8iqcl6cpaagisdxfwdaxma4jnygi1qwbzjmm";
  };
  src3 = builtins.fetchurl {
    url = "https://repo1.maven.org/maven2/org/apktool/brut.j.util/${version}/brut.j.util-${version}.jar";
    sha256 = "02facqysvrjyh8asrxsfy8hdj1lnpiwrcbgy8fv26l8hbi4b2fgg";
  };
  src4 = builtins.fetchurl {
    url = "https://repo1.maven.org/maven2/org/apktool/brut.j.common/${version}/brut.j.common-${version}.jar";
    sha256 = "19q0cdlwsalnskgdgh24rgnfd9b39vyzb54na5vsbwd7n5psivnx";
  };
  src5 = builtins.fetchurl {
    url = "https://repo1.maven.org/maven2/xpp3/xpp3/1.1.4c/xpp3-1.1.4c.jar";
    sha256 = "1f9ifnxxj295xb1494jycbfm76476xm5l52p7608gf0v91d3jh83";
  };
  src6 = builtins.fetchurl {
    url = "https://repo1.maven.org/maven2/commons-io/commons-io/2.15.1/commons-io-2.15.1.jar";
    sha256 = "0m71akmdfpr7ri41mj0fl20l622g2vpwl9qcpcpgv35nw4pg32m5";
  };

  phases = ["installPhase"];

  installPhase = ''
    mkdir -p $out/lib
    cp ${src} ${src2} ${src3} ${src4} ${src5} ${src6} $out/lib/
    find $out/lib
  '';
}
