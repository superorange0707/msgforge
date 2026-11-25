# Third-party software

msgforge's own code is under the Apache License 2.0 (`LICENSE`). This repository holds none of the libraries below; a release (the zip, the container image) carries them as jars, each under its own terms.

In the zip, `licenses/` holds the licence and notice files those jars bring with them, one folder per jar. Texts that no jar brings are at the end of this file; the Eclipse Public License 2.0 itself is in `licenses/jakarta.jms-api-*/LICENSE.md`. The build stops if a release would carry a jar this file does not name.

## In the application (`msgforge.jar`)

| Jars | Project | Licence |
|---|---|---|
| `spring-boot`, `spring-boot-autoconfigure`, and what the Spring Boot build adds to the application jar: its loader classes and `spring-boot-jarmode-tools` | Spring Boot | Apache-2.0 |
| `spring-aop`, `spring-beans`, `spring-context`, `spring-core`, `spring-expression`, `spring-jcl`, `spring-jms`, `spring-messaging`, `spring-tx`, `spring-web`, `spring-webmvc` | Spring Framework | Apache-2.0 |
| `tomcat-embed-core`, `tomcat-embed-el`, `tomcat-embed-websocket` | Apache Tomcat | Apache-2.0; `tomcat-embed-core` lists its other parts in its own licence file |
| `jackson-annotations`, `jackson-core`, `jackson-databind`, `jackson-datatype-jdk8`, `jackson-datatype-jsr310`, `jackson-module-parameter-names` | Jackson | Apache-2.0; `jackson-core` includes FastDoubleParser (MIT) |
| `micrometer-commons`, `micrometer-observation` | Micrometer | Apache-2.0 |
| `log4j-api`, `log4j-to-slf4j` | Apache Log4j | Apache-2.0 |
| `snakeyaml` | SnakeYAML | Apache-2.0 |
| `slf4j-api`, `jul-to-slf4j` | SLF4J | MIT |
| `logback-classic`, `logback-core` | Logback | EPL-2.0 or LGPL-2.1; carried here under the Eclipse Public License 2.0, <https://www.eclipse.org/legal/epl-v20.html>. Source code: <https://github.com/qos-ch/logback> |
| `jakarta.jms-api`, `jakarta.annotation-api` | Jakarta Messaging, Jakarta Annotations | EPL-2.0 or GPL-2.0 with Classpath exception; carried here under the Eclipse Public License 2.0. Each jar's notice file says where its source code is |
| `bcprov-jdk18on`, `bcpkix-jdk18on`, `bcutil-jdk18on` | Bouncy Castle | Bouncy Castle Licence (MIT), text below |
| `json` | JSON-java | Public domain |
| `com.ibm.mq.jakarta.client` | IBM MQ classes for JMS | **Not open source.** IBM International Program License Agreement, <https://www.ibm.com/support/customer/csol/terms/?id=L-ASCR-N5A7RX> |

The IBM MQ client is the property of IBM (Licensed Materials - Property of IBM, (c) Copyright IBM Corp. 2008, 2026) and msgforge's licence does not extend to it. Whoever uses or passes on a release that contains it does so under IBM's terms.

## In the Kafka plugin (`plugins/msgforge-kafka-*.jar`)

| Jars | Project | Licence |
|---|---|---|
| `kafka-clients` | Apache Kafka | Apache-2.0 |
| `lz4-java` | lz4-java | Apache-2.0; includes the LZ4 library and xxHash (both BSD 2-Clause, texts below) |
| `snappy-java` | snappy-java | Apache-2.0, notice below; includes Snappy (BSD 3-Clause, text below) |
| `zstd-jni` | zstd-jni | BSD 2-Clause, text below; includes Zstandard (BSD 3-Clause, text below) |

## In the RabbitMQ plugin (`plugins/msgforge-rabbitmq-*.jar`)

| Jars | Project | Licence |
|---|---|---|
| `amqp-client` | RabbitMQ Java client | Apache-2.0, MPL-2.0 or GPL-2.0; carried here under the Apache License 2.0 |

## In the MQTT plugin (`plugins/msgforge-mqtt-*.jar`)

| Jars | Project | Licence |
|---|---|---|
| `org.eclipse.paho.client.mqttv3`, `org.eclipse.paho.mqttv5.client` | Eclipse Paho Java client | EPL-2.0. Source code: <https://github.com/eclipse/paho.mqtt.java> |

## In the AMQP 1.0 plugin (`plugins/msgforge-amqp-*.jar`)

| Jars | Project | Licence |
|---|---|---|
| `qpid-jms-client`, `proton-j` | Apache Qpid JMS, Apache Qpid Proton-J | Apache-2.0 |
| `netty-buffer`, `netty-codec`, `netty-codec-http`, `netty-common`, `netty-handler`, `netty-resolver`, `netty-transport`, `netty-transport-native-unix-common` | Netty | Apache-2.0 |

## In the Amazon SQS plugin (`plugins/msgforge-sqs-*.jar`)

| Jars | Project | Licence |
|---|---|---|
| `sqs`, `annotations`, `auth`, `aws-core`, `aws-json-protocol`, `checksums`, `checksums-spi`, `endpoints-spi`, `eventstream`, `http-auth`, `http-auth-aws`, `http-auth-aws-eventstream`, `http-auth-spi`, `http-client-spi`, `identity-spi`, `json-utils`, `metrics-spi`, `profiles`, `protocol-core`, `regions`, `retries`, `retries-spi`, `sdk-core`, `url-connection-client`, `utils`, `utils-lite` | AWS SDK for Java 2 | Apache-2.0 |
| `third-party-jackson-core` | AWS SDK for Java 2 (a repackaged Jackson core) | Apache-2.0; includes FastDoubleParser (MIT), its licence files are in the jar |
| `reactive-streams` | Reactive Streams | MIT-0, text below |

## In the container image

The image is built on `eclipse-temurin:17-jre`; the Java runtime and the operating system in it come with their own licences, inside the image.

## Licence texts and notices no jar brings

### Reactive Streams (MIT No Attribution)

```
Permission is hereby granted, free of charge, to any person obtaining a copy of this
software and associated documentation files (the "Software"), to deal in the Software
without restriction, including without limitation the rights to use, copy, modify,
merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### Bouncy Castle

```
Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software
and associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

### snappy-java (notice)

```
This product includes software developed by Google
 Snappy: http://code.google.com/p/snappy/ (New BSD License)

This product includes software developed by Apache
 PureJavaCrc32C from apache-hadoop-common http://hadoop.apache.org/
 (Apache 2.0 license)

This library contains statically linked libstdc++. This inclusion is allowed by
"GCC Runtime Library Exception"
http://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html

== Contributors ==
  * Tatu Saloranta
    * Providing benchmark suite
  * Alec Wysoker
    * Performance and memory usage improvement

Third-Party Notices and Licenses:

- Hadoop: Apache Hadoop is used as a dependency
  License: Apache License 2.0
  Source/Reference: https://github.com/apache/hadoop/blob/trunk/NOTICE.txt
```

### Snappy

```
Copyright 2011, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### LZ4 library

```
LZ4 Library
Copyright (c) 2011-2020, Yann Collet
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice, this
  list of conditions and the following disclaimer in the documentation and/or
  other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### xxHash

```
   xxHash - Extremely Fast Hash algorithm
   Header File
   Copyright (C) 2012-2016, Yann Collet.

   BSD 2-Clause License (http://www.opensource.org/licenses/bsd-license.php)

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions are
   met:

       * Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.
       * Redistributions in binary form must reproduce the above
   copyright notice, this list of conditions and the following disclaimer
   in the documentation and/or other materials provided with the
   distribution.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
   A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
   OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
   SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
   LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
   DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
   THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
   (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
   OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

   You can contact the author at :
   - xxHash source repository : https://github.com/Cyan4973/xxHash
```

### zstd-jni

```
Zstd-jni: JNI bindings to Zstd Library

Copyright (c) 2015-present, Luben Karavelov/ All rights reserved.

BSD License

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice, this
  list of conditions and the following disclaimer in the documentation and/or
  other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Zstandard

```
BSD License

For Zstandard software

Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

 * Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

 * Neither the name Facebook, nor Meta, nor the names of its contributors may
   be used to endorse or promote products derived from this software without
   specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
