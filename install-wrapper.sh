#!/bin/bash
# Download gradle wrapper
mkdir -p gradle/wrapper
cd gradle/wrapper

# Download gradle wrapper jar
wget https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradle-wrapper.jar -O gradle-wrapper.jar

cd ../..

# Create gradlew script
cat > gradlew << 'EOF'
#!/bin/sh
exec java -jar gradle/wrapper/gradle-wrapper.jar "$@"
EOF

chmod +x gradlew

# Update wrapper properties for gradle 8.5
cat > gradle/wrapper/gradle-wrapper.properties << 'EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF

echo "Gradle wrapper installed successfully!"
