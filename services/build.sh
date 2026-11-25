#!/bin/sh
# Build the service layer. One dependency: the SQLite JDBC driver in lib/
# (MRD-77). The JDK HTTP server is all that is left of the 2014 Spring
# application.
set -e
cd "$(dirname "$0")"
mkdir -p build/classes
javac -d build/classes $(find src -name '*.java')
# The driver is unpacked beside the service's own classes, so everything
# that already runs "java -cp services/build/classes ..." keeps working
# with no change to its class path.
(cd build/classes && jar xf ../../lib/sqlite-jdbc-3.46.1.3.jar)
echo "built services/build/classes"
