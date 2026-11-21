#!/bin/sh
# Run the services tests.
#
# There is no test framework here, as there is no build tool: the tests
# are plain Java with a main method. They need the JDK and the sqlite3
# command line tool. The service itself no longer runs that tool (MRD-77),
# but the tests build their stores with it and hold the service's answers
# against what it prints, so run them where it is installed: inside the
# devcontainer, or in CI.
#
#   ./services/test.sh
#
# Exit status is 0 only if every check passed. When a response differs from
# the recorded one, the response that came back is saved under
# services/build/test-actual/ so the two can be compared.
set -e
cd "$(dirname "$0")"

# Said first, because the service's answers are held against what this
# sqlite3 prints, and the recordings under test/expected were made with 3.45.
echo "sqlite3 $(sqlite3 --version | cut -d' ' -f1), $(java -version 2>&1 | head -n 1)"

./build.sh

rm -rf build/test-classes build/test-actual
mkdir -p build/test-classes
# CI builds with JDK 17 and the devcontainer has 21: hold the tests to 17.
javac --release 17 -cp build/classes -d build/test-classes $(find test -name '*.java')

exec java -cp build/classes:build/test-classes ca.meridian.test.RunTests test
