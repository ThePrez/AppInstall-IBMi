
BUILDVERSION:="Development build \(built with Make\)"

JAVA_SRCS := $(shell find src -type f)

appinstall-v%.jar: ${JAVA_SRCS}
	echo "Building version $*"
	mvn -Djava.net.preferIPv4Stack=true "-Dappinstall.version=$*" package
	cp target/appinstall-*-with-dependencies.jar appinstall-v$*.jar

appinstall-gmake.jar: ${JAVA_SRCS} 
	mvn -Djava.net.preferIPv4Stack=true -Dappinstall.version=${BUILDVERSION} package
	cp target/appinstall-*-with-dependencies.jar appinstall-gmake.jar

all: appinstall-gmake.jar