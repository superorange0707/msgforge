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
| `org.eclipse.paho.client.mqttv3`, `org.eclipse.paho.mqttv5.client` | Eclipse Paho Java client | EPL-2.0 or EDL-1.0; carried here under the Eclipse Public License 2.0. Source code: <https://github.com/eclipse/paho.mqtt.java> |

## In the AMQP 1.0 plugin (`plugins/msgforge-amqp-*.jar`)

| Jars | Project | Licence |
|---|---|---|
| `qpid-jms-client`, `proton-j` | Apache Qpid JMS, Apache Qpid Proton-J | Apache-2.0 |
| `netty-buffer`, `netty-codec`, `netty-codec-http`, `netty-common`, `netty-handler`, `netty-resolver`, `netty-transport`, `netty-transport-native-unix-common` | Netty | Apache-2.0; its notice, which lists the code it includes, is below |

## In the Amazon SQS plugin (`plugins/msgforge-sqs-*.jar`)

| Jars | Project | Licence |
|---|---|---|
| `sqs`, `annotations`, `auth`, `aws-core`, `aws-json-protocol`, `checksums`, `checksums-spi`, `endpoints-spi`, `eventstream`, `http-auth`, `http-auth-aws`, `http-auth-aws-eventstream`, `http-auth-spi`, `http-client-spi`, `identity-spi`, `json-utils`, `metrics-spi`, `profiles`, `protocol-core`, `regions`, `retries`, `retries-spi`, `sdk-core`, `url-connection-client`, `utils`, `utils-lite` | AWS SDK for Java 2 | Apache-2.0 |
| `third-party-jackson-core` | AWS SDK for Java 2 (a repackaged Jackson core) | Apache-2.0; includes FastDoubleParser (MIT), its licence files are in the jar |
| `reactive-streams` | Reactive Streams | MIT-0, text below |

## In the NATS plugin (`plugins/msgforge-nats-*.jar`)

| Jars | Project | Licence |
|---|---|---|
| `jnats` | NATS Java client | Apache-2.0 |
| `bcprov-lts8on` | Bouncy Castle (used by the client for NKey signatures) | Bouncy Castle Licence (MIT), text below |
| `jspecify` | JSpecify annotations | Apache-2.0 |

## In the Apache Pulsar plugin (`extensions/pulsar`, not in the release)

The release does not carry this plugin; `./gradlew :extensions:pulsar:installPlugin` builds it. It is named here because the build checks every plugin jar it makes.

| Jars | Project | Licence |
|---|---|---|
| `pulsar-client`, `pulsar-client-api`, `pulsar-client-admin-api` | Apache Pulsar | Apache-2.0. `pulsar-client` is a shaded jar: it also contains, renamed, Netty (with netty-tcnative and BoringSSL native libraries), Guava, Jackson, Gson, Caffeine, RE2/J, fastutil, DataSketches, aircompressor, Swagger annotations, JavaBeans Activation and others, and carries none of their licence or notice files. Their terms have not been reviewed here. |
| `bcprov-jdk18on`, `bcpkix-jdk18on`, `bcutil-jdk18on` | Bouncy Castle | Bouncy Castle Licence (MIT), text below |
| `opentelemetry-api`, `opentelemetry-api-incubator`, `opentelemetry-common`, `opentelemetry-context` | OpenTelemetry Java | Apache-2.0 |
| `validation-api` | Bean Validation API | Apache-2.0 |
| `jackson-annotations` | Jackson | Apache-2.0 |

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

### Netty (notice, version 4.1.135.Final)

```
                            The Netty Project
                            =================

Please visit the Netty web site for more information:

  * https://netty.io/

Copyright 2014 The Netty Project

The Netty Project licenses this file to you under the Apache License,
version 2.0 (the "License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at:

  https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations
under the License.

Also, please refer to each LICENSE.<component>.txt file, which is located in
the 'license' directory of the distribution file, for the license terms of the
components that this product depends on.

-------------------------------------------------------------------------------
This product contains the extensions to Java Collections Framework which has
been derived from the works by JSR-166 EG, Doug Lea, and Jason T. Greene:

  * LICENSE:
    * license/LICENSE.jsr166y.txt (Public Domain)
  * HOMEPAGE:
    * http://gee.cs.oswego.edu/cgi-bin/viewcvs.cgi/jsr166/
    * http://viewvc.jboss.org/cgi-bin/viewvc.cgi/jbosscache/experimental/jsr166/

This product contains a modified version of Robert Harder's Public Domain
Base64 Encoder and Decoder, which can be obtained at:

  * LICENSE:
    * license/LICENSE.base64.txt (Public Domain)
  * HOMEPAGE:
    * http://iharder.sourceforge.net/current/java/base64/

This product contains a modified portion of 'Webbit', an event based
WebSocket and HTTP server, which can be obtained at:

  * LICENSE:
    * license/LICENSE.webbit.txt (BSD License)
  * HOMEPAGE:
    * https://github.com/joewalnes/webbit

This product contains a modified portion of 'SLF4J', a simple logging
facade for Java, which can be obtained at:

  * LICENSE:
    * license/LICENSE.slf4j.txt (MIT License)
  * HOMEPAGE:
    * https://www.slf4j.org/

This product contains a modified portion of 'Apache Harmony', an open source
Java SE, which can be obtained at:

  * NOTICE:
    * license/NOTICE.harmony.txt
  * LICENSE:
    * license/LICENSE.harmony.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://archive.apache.org/dist/harmony/

This product contains a modified portion of 'jbzip2', a Java bzip2 compression
and decompression library written by Matthew J. Francis. It can be obtained at:

  * LICENSE:
    * license/LICENSE.jbzip2.txt (MIT License)
  * HOMEPAGE:
    * https://code.google.com/p/jbzip2/

This product contains a modified portion of 'libdivsufsort', a C API library to construct
the suffix array and the Burrows-Wheeler transformed string for any input string of
a constant-size alphabet written by Yuta Mori. It can be obtained at:

  * LICENSE:
    * license/LICENSE.libdivsufsort.txt (MIT License)
  * HOMEPAGE:
    * https://github.com/y-256/libdivsufsort

This product contains a modified portion of Nitsan Wakart's 'JCTools', Java Concurrency Tools for the JVM,
 which can be obtained at:

  * LICENSE:
    * license/LICENSE.jctools.txt (ASL2 License)
  * HOMEPAGE:
    * https://github.com/JCTools/JCTools

This product optionally depends on 'JZlib', a re-implementation of zlib in
pure Java, which can be obtained at:

  * LICENSE:
    * license/LICENSE.jzlib.txt (BSD style License)
  * HOMEPAGE:
    * http://www.jcraft.com/jzlib/

This product optionally depends on 'Compress-LZF', a Java library for encoding and
decoding data in LZF format, written by Tatu Saloranta. It can be obtained at:

  * LICENSE:
    * license/LICENSE.compress-lzf.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/ning/compress

This product optionally depends on 'lz4', a LZ4 Java compression
and decompression library written by Adrien Grand. It can be obtained at:

  * LICENSE:
    * license/LICENSE.lz4.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/jpountz/lz4-java

This product optionally depends on 'lzma-java', a LZMA Java compression
and decompression library, which can be obtained at:

  * LICENSE:
    * license/LICENSE.lzma-java.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/jponge/lzma-java

This product optionally depends on 'zstd-jni', a zstd-jni Java compression
and decompression library, which can be obtained at:

  * LICENSE:
    * license/LICENSE.zstd-jni.txt (BSD)
  * HOMEPAGE:
    * https://github.com/luben/zstd-jni

This product contains a modified portion of 'jfastlz', a Java port of FastLZ compression
and decompression library written by William Kinney. It can be obtained at:

  * LICENSE:
    * license/LICENSE.jfastlz.txt (MIT License)
  * HOMEPAGE:
    * https://code.google.com/p/jfastlz/

This product contains a modified portion of and optionally depends on 'Protocol Buffers', Google's data
interchange format, which can be obtained at:

  * LICENSE:
    * license/LICENSE.protobuf.txt (New BSD License)
  * HOMEPAGE:
    * https://github.com/google/protobuf

This product optionally depends on 'Bouncy Castle Crypto APIs' to generate
a temporary self-signed X.509 certificate when the JVM does not provide the
equivalent functionality.  It can be obtained at:

  * LICENSE:
    * license/LICENSE.bouncycastle.txt (MIT License)
  * HOMEPAGE:
    * https://www.bouncycastle.org/

This product optionally depends on 'Snappy', a compression library produced
by Google Inc, which can be obtained at:

  * LICENSE:
    * license/LICENSE.snappy.txt (New BSD License)
  * HOMEPAGE:
    * https://github.com/google/snappy

This product optionally depends on 'JBoss Marshalling', an alternative Java
serialization API, which can be obtained at:

  * LICENSE:
    * license/LICENSE.jboss-marshalling.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/jboss-remoting/jboss-marshalling

This product optionally depends on 'Caliper', Google's micro-
benchmarking framework, which can be obtained at:

  * LICENSE:
    * license/LICENSE.caliper.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/google/caliper

This product optionally depends on 'Apache Commons Logging', a logging
framework, which can be obtained at:

  * LICENSE:
    * license/LICENSE.commons-logging.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://commons.apache.org/logging/

This product optionally depends on 'Apache Log4J', a logging framework, which
can be obtained at:

  * LICENSE:
    * license/LICENSE.log4j.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://logging.apache.org/log4j/

This product optionally depends on 'Aalto XML', an ultra-high performance
non-blocking XML processor, which can be obtained at:

  * LICENSE:
    * license/LICENSE.aalto-xml.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://wiki.fasterxml.com/AaltoHome

This product contains a modified version of 'HPACK', a Java implementation of
the HTTP/2 HPACK algorithm written by Twitter. It can be obtained at:

  * LICENSE:
    * license/LICENSE.hpack.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/twitter/hpack

This product contains a modified version of 'HPACK', a Java implementation of
the HTTP/2 HPACK algorithm written by Cory Benfield. It can be obtained at:

  * LICENSE:
    * license/LICENSE.hyper-hpack.txt (MIT License)
  * HOMEPAGE:
    * https://github.com/python-hyper/hpack/

This product contains a modified version of 'HPACK', a Java implementation of
the HTTP/2 HPACK algorithm written by Tatsuhiro Tsujikawa. It can be obtained at:

  * LICENSE:
    * license/LICENSE.nghttp2-hpack.txt (MIT License)
  * HOMEPAGE:
    * https://github.com/nghttp2/nghttp2/

This product contains a modified portion of 'Apache Commons Lang', a Java library
provides utilities for the java.lang API, which can be obtained at:

  * LICENSE:
    * license/LICENSE.commons-lang.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://commons.apache.org/proper/commons-lang/


This product contains the Maven wrapper scripts from 'Maven Wrapper', that provides an easy way to ensure a user has everything necessary to run the Maven build.

  * LICENSE:
    * license/LICENSE.mvn-wrapper.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/takari/maven-wrapper

This product contains the dnsinfo.h header file, that provides a way to retrieve the system DNS configuration on MacOS.
This private header is also used by Apple's open source
 mDNSResponder (https://opensource.apple.com/tarballs/mDNSResponder/).

 * LICENSE:
    * license/LICENSE.dnsinfo.txt (Apple Public Source License 2.0)
  * HOMEPAGE:
    * https://www.opensource.apple.com/source/configd/configd-453.19/dnsinfo/dnsinfo.h

This product optionally depends on 'Brotli4j', Brotli compression and
decompression for Java., which can be obtained at:

  * LICENSE:
    * license/LICENSE.brotli4j.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/hyperxpro/Brotli4j
```
