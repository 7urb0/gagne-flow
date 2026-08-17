#!/bin/bash
# mvn-wrapper.sh - git bash 下调用 Maven 的可靠方式
# 原因: mvn.sh 在 git bash 下路径转换失效(/c/... 传给 java.exe 不识别),
#       手动用 cygpath 转 Windows 路径 + java 直接启动 launcher。
# 用法: ./mvn-wrapper.sh <maven参数...>   例: ./mvn-wrapper.sh -v
MVNHOME="/c/Users/niu23/.m2/wrapper/dists/apache-maven-3.9.10-bin/53h08a94dg6djh6umvruv7q564/apache-maven-3.9.10"
JAVA="D:/JDK17/bin/java.exe"
CW=$(cygpath -w "$MVNHOME/boot/plexus-classworlds-2.9.0.jar")
CONF=$(cygpath -w "$MVNHOME/bin/m2.conf")
MH=$(cygpath -w "$MVNHOME")
"$JAVA" --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
  -classpath "$CW" -Dclassworlds.conf="$CONF" -Dmaven.home="$MH" \
  -Dmaven.multiModuleProjectDirectory="$(pwd)" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
