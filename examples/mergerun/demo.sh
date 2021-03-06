# Copyright (c) 2018, salesforce.com, inc.
# All rights reserved.
# Licensed under the BSD 3-Clause license.
# For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause

# Hello World example for the Salesforce Bazel migration tool

#
# first do the dependency:list analysis on the sample Maven project
echo ""
echo "**********************************************************************************"
echo "STEP 0: Copy in the existing external dependencies from the WORKSPACE to the inputs directory"
cp ../WORKSPACE inputs/WORKSPACE.copy
cp ../external_deps.* inputs

# do the dependency:list analysis on the sample Maven project
echo ""
echo "**********************************************************************************"
echo "STEP 1: Generating the dependency list for the sample another-maven-project"
pushd .
cd another-maven-project
mvn -Dsort dependency:list > ../inputs/another-maven-project-deps.txt
popd
cat inputs/another-maven-project-deps.txt

# build the migration tool
pushd .
cd ../..
echo ""
echo "**********************************************************************************"
echo "STEP 2: Building the migration tool"
mvn -DskipTests install
popd

# run the migration tool to generate the WORKSPACE entries for the external deps
echo ""
echo "**********************************************************************************"
echo "STEP 3: generate the file that includes all the external dependencies from my-maven-project for the WORKSPACE"
java -jar ../../target/maventobazel-generator-1.0.0.jar --workspace
cat outputs/external_deps.bzl.out


# run the migration tool to generate the BUILD entries for the external deps
echo ""
echo "**********************************************************************************"
echo "STEP 4: generate the file that lists all the external dependencies from my-maven-project for the BUILD file"
java -jar ../../target/maventobazel-generator-1.0.0.jar --build
cat outputs/BUILD.out
