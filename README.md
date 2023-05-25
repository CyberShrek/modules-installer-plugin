# modules-installer-plugin
> Плагин, обеспечивающий преобразование Maven-зависимостей в модули с последующей их установкой на сервер приложений

### Цели создания плагина:
- централизировать управление библиотеками, используемых приложениями
- облегчить процесс внедрения новых библиотек
- оптимизировать построение модулей и их зависимостей
---
# Быстрый гайд
### 1. Определяем модули в pom.xml
Для начала нам нужен Maven проект, в котором мы планируем описать модули. Это может быть как уже существующий, так и новый проект; главное, чтобы в нём был файл pom.xml. Сам проект будет выступать в роли корневого модуля. 

Для демонстрации я создам пустой проект, у которого нет ничего кроме описания pom.xml:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
<modelVersion>4.0.0</modelVersion>
    
    <groupId>org.vniizht</groupId>
    <artifactId>god-module</artifactId>
    <version>demo</version>

    <properties>
        <java.version>1.8</java.version>
        
        <!-- Чтобы избежать возможных проблем с компиляцией, на всякий случай опишем следующее -->
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.source>${java.version}</maven.compiler.source>
        <maven.compiler.target>${java.version}</maven.compiler.target>
    </properties>

    <!-- Добавим зависимости, которые планируем внедрять -->
    <dependencies>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>42.6.0</version>
        </dependency>

        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-stdlib-jdk8</artifactId>
            <version>1.6.21</version>
        </dependency>

        <dependency>
            <groupId>org.hibernate</groupId>
            <artifactId>hibernate-core</artifactId>
            <version>5.6.15.Final</version>
        </dependency>

        <dependency>
            <groupId>com.google.guava</groupId>
            <artifactId>guava</artifactId>
            <version>31.1-jre</version>
        </dependency>

        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml</artifactId>
            <version>5.2.3</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Сюда в дальнейшем положим наш плагин, об этом подробнее будет ниже -->
        </plugins>
    </build>
</project>
```

### 2. Добавляем modules-installer-plugin
Для начала следует его установить локально. Для этого скачиваем саму jar-библиотеку 