pipeline {
    agent any

    environment {
        JAVA_HOME = tool name: 'jdk17', type: 'jdk'
        MAVEN_HOME = tool name: 'maven3', type: 'maven'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Checkstyle') {
            steps {
                sh 'mvn checkstyle:check -Dcheckstyle.failOnViolation=true'
            }
        }

        stage('Unit Test') {
            steps {
                sh 'mvn test -Dspring.profiles.active=test'
                sh 'mvn jacoco:report'
            }
            post {
                always {
                    junit 'target/surefire-reports/**/*.xml'
                    jacoco execPattern: 'target/jacoco.exec',
                          classPattern: 'target/classes',
                          sourcePattern: 'src/main/java'
                }
            }
        }

        stage('Build') {
            steps {
                sh 'mvn package -DskipTests'
            }
        }

        stage('Archive') {
            steps {
                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
            }
        }
    }

    post {
        failure {
            emailext (
                subject: "[CI] GagneFlow 构建失败: ${env.BUILD_NUMBER}",
                body: "构建 #${env.BUILD_NUMBER} 失败。\n详情: ${env.BUILD_URL}",
                to: 'dev-team@example.com'
            )
        }
    }
}
