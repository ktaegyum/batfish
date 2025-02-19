# Building and running Batfish and its tests

Batfish runs as a service that can be accessed via RESTful APIs.
A [Python client](https://github.com/batfish/pybatfish) comes bundled
with
the [suggested allinone Docker container](https://github.com/batfish/batfish#how-do-i-get-started)
or can be [set up separately](https://github.com/batfish/pybatfish#how-do-i-get-started).

## Prerequisites

- Java 11 JDK
- git
- [bazelisk](https://github.com/bazelbuild/bazelisk#installation)

We provide OS-specific advice below.

* `Ubuntu`: Read [Ubuntu notes](#ubuntu) first
* `macOS`: Read [macOS notes](#macos) first

### Operation-system specific prerequisite installation

### macOS

Do the following before doing anything

1. Install [XCode from Apple Store](https://itunes.apple.com/us/app/xcode/id497799835)

2. Install XCode command-line tools.
    - `xcode-select --install`

3. Install Homebrew. Follow [these steps](https://brew.sh/).

4. Open a fresh terminal to ensure the utilities are correctly picked up.

5. If you don't already have the Java 11 JDK installed, first install homebrew cask and then Java 11
   using the following commands.
    - `brew tap homebrew/cask-versions`
    - `brew install --cask temurin11`

6. If you don't already have it, install Bazelisk.
    - `brew install bazelisk`

### Ubuntu

Do the following before doing anything

1. Install Java 11 and corresponding debug symbols
    - `sudo apt install openjdk-11-jdk openjdk-11-dbg`

2. If you don't already have it, install Bazelisk.
    - Open the [bazelisk release page](https://github.com/bazelbuild/bazelisk/releases)
    - Copy the link for the `bazelisk-linux-amd64` release asset for the latest version
    - Download and install bazelisk using the copied link (example url below is for `v1.12.2`, but
      you should use the latest version):

      `curl -s https://github.com/bazelbuild/bazelisk/releases/download/v1.12.2/bazelisk-linux-amd64 | sudo tee /usr/local/bin/bazelisk`
    - Make bazelisk executable:

      `sudo chmod +x /usr/local/bin/bazelisk`
    - Symlink bazel to bazelisk:

      `sudo ln -s bazelisk /usr/local/bin/bazel`

### Note: multiple versions of Java

If you have multiple versions of Java installed on your machine, the default `java`/`javac` commands may still not be using JVM 11. In that case, you can force the version of Java in use by setting `JAVA_HOME`. @dhalperi has these aliases in his `.zshrc` to control which Java is running in a given shell on macOS:

```sh
# Java options
# j8q switches to Java 8, quietly. Could make a loud version that runs `java -version` after.
function j8q() {
    export JAVA_HOME=`/usr/libexec/java_home -v 1.8`
}
function j11q() {
    export JAVA_HOME=`/usr/libexec/java_home -v 11`
}
# Default to Java 11.
j11q
```

## Installation steps

1. Check out the Batfish code
    - `git clone https://github.com/batfish/batfish.git`
    - `cd batfish`

2. Compile Batfish
    - `bazel build //projects/allinone:allinone_main`

3. Run the Batfish service
    - ```
      bazel run //projects/allinone:allinone_main -- -runclient false -coordinatorargs "-templatedirs $(git rev-parse --show-toplevel)/questions -containerslocation $(git rev-parse --show-toplevel)/containers"
      ```
      will start Batfish in service mode using the library of questions within the Batfish
      repository.

   Data stored by the service to analyze a network will be stored in a `containers` folder.

   If you wish to provide Java arguments, use the `--jvmopt` option once for each java argument in
   between `run` and `//projects/allinone:allinone_main`:

   To set the RAM:
    ```
    bazel run --jvmopt=-Xmx2g //projects/allinone:allinone_main -- -runclient false -coordinatorargs "-templatedirs $(git rev-parse --show-toplevel)/questions -containerslocation $(git rev-parse --show-toplevel)/containers"
    ```

   To turn on assertions and enable a debugger to attach:
    ```
    bazel run --jvmopt=-ea --jvmopt=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5009 //projects/allinone:allinone_main -- -runclient false -coordinatorargs "-templatedirs $(git rev-parse --show-toplevel)/questions -containerslocation $(git rev-parse --show-toplevel)/containers"
    ```

   For more info, see the Bazel documentation.

4. Explore using Pybatfish

   Once the service is running, you can
   use [Pybatfish](https://github.com/batfish/pybatfish) to analyze
   your network.

## Running tests

You can run all tests in this repository by running:

```
bazel test //...
```

The above is a specific instance of the general form for running all tests in all Bazel packages
in a subtree. The above uses the entire repository `//` as the root; to test only the `coordinator`:

```
bazel test //projects/coordinator/...
```

To run a specific test, just use its Bazel target:

```
bazel test //projects/batfish:pmd
```

You can also combine multiple test target expressions:

```
bazel test //projects/coordinator/... //projects/batfish:pmd
```

If you want to test just a single function of a rule of type `junit_test`, do:

```
bazel test --test_filter=<fully-qualified-class-name>#test-function$ -- <test-target-containing-test-class>
```

For example, to run just the `getNonExistNetwork` test function of the `WorkMgrServiceTest` class,
do:

```
bazel test --test_filter=org.batfish.coordinator.WorkMgrServiceTest#getNonExistNetwork$ -- //projects/coordinator:coordinator_tests
```

## Building a deployable batfish or allinone docker image

- Use the batfish image if you just want the batfish service.
- Use the allinone image if you want the batfish service and a built-in jupyter notebook server.

### Building images locally

This section of the doc is still in progress. Check back later!

### Building images via GitHub actions

This section of the doc is still in progress. Check back later!
