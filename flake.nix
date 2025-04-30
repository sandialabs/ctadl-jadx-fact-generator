{
  inputs = {
    flake-utils.url = "github:numtide/flake-utils";
    flake-compat = {
      url = "github:edolstra/flake-compat";
      flake = false;
    };
    merjar.url = "github:dbueno/merjar";
    merjar.inputs.nixpkgs.follows = "nixpkgs";
    jadx.url = "github:dbueno/jadx?ref=source-maps";
    dex2jar.url = "github:dbueno/dex2jar?ref=add-bytecode-offset-api";
  };
  outputs = {
    self,
    nixpkgs,
    flake-utils,
    flake-compat,
    merjar,
    jadx,
    dex2jar,
  }: let
    jadxFactgenOverlay = final: prev: {
      jadx = jadx.defaultPackage."${final.stdenv.system}";
      dex2jar = dex2jar.defaultPackage."${final.stdenv.system}";
      merjar = merjar.defaultPackage."${final.stdenv.system}";
      apktool-lib = final.callPackage ./nix/apktool-lib.nix {};
      jadxFactgen = final.callPackage ./nix/jadx-factgen.nix {};
      jadxFactgenPlugin = final.callPackage ./ctadl_plugin/default.nix {};
      dist = final.callPackage ./dist.nix {};
    };
  in
    {overlays = {inherit jadxFactgenOverlay;};}
    // (
      flake-utils.lib.eachDefaultSystem (system: let
        pkgs = import nixpkgs {
          inherit system;
        };
        jadxFactgenPackages = let
          scope = pkgs.lib.fixedPoints.extends jadxFactgenOverlay (self: {
            stdenv = pkgs.stdenv;
            jdk = pkgs.jdk11;
          });
        in
          pkgs.lib.makeScope pkgs.newScope scope;

        # Remember to change in App.java and setup.cfg
        jadxFactgen = jadxFactgenPackages.jadxFactgen;
        singularityImage = pkgs.singularity-tools.buildImage {
          name = "jadx_factgen-${jadxFactgen.version}";
          diskSize = 1024 * 8;
          memSize = 1024 * 4;
          contents = [pkgs.bashInteractive] ++ [jadxFactgen];
          runScript = "/bin/jadx_factgen";
        };
      in {
        formatter = pkgs.alejandra;
        packages = {
          inherit jadxFactgenPackages;
          inherit (jadxFactgenPackages) jadxFactgen jadxFactgenPlugin dist;
          singularity = singularityImage;
        };
        defaultPackage = jadxFactgenPackages.jadxFactgen;
        defaultApp = {
          type = "app";
          program = "${jadxFactgen}/bin/ctadl-jadx-fact-generator";
        };
        devShell = let
          dev-python = pkgs.python39.withPackages (python-packages:
            with python-packages; [
              wheel
              pip
              setuptools
              # other python packages you want
            ]);
        in
          pkgs.mkShell {
            inputsFrom = [jadx];
            packages = [dev-python jadxFactgenPackages.jdk] ++ (with pkgs; [jdt-language-server pyright black]);
            hardeningDisable = ["all"];
            shellHook = let
              java_home =
                if pkgs.stdenv.isDarwin
                then "$(dirname $(dirname $(realpath ${pkgs.jdk11.home}/bin/java)))"
                else "${pkgs.jdk11.home}";
            in ''
              export JAVA_HOME="${pkgs.jdk11.home}"
              export JDTLS_GRADLE_JAVA_HOME="${java_home}"
              export JAVA8_HOME="${pkgs.jdk8.home}"
              export JAVA11_HOME="${pkgs.jdk11.home}"
              export JADX_HOME="${jadxFactgenPackages.jadx}"
              export DEX2JAR_LIB="${jadxFactgenPackages.dex2jar}/lib"
              export DEX2JAR_VERSION="2.x"
              export PYTHONPATH=${dev-python}/${dev-python.sitePackages}
            '';
          };
      })
    );
}
