/**
 * Copyright (C) 2013 all@code-story.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package net.codestory.http;

import static java.nio.charset.StandardCharsets.*;
import static net.codestory.http.GetAssert.*;
import static net.codestory.http.PostAssert.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.util.*;

import net.codestory.http.annotations.*;
import net.codestory.http.internal.*;
import net.codestory.http.payload.*;
import net.codestory.http.templating.*;

import org.junit.*;

public class WebServerTest {
  static WebServer server = new WebServer() {
    @Override
    protected boolean devMode() {
      return false;
    }
  };

  @BeforeClass
  public static void startServer() {
    server.startOnRandomPort();
  }

  @Before
  public void resetWebServer() {
    server.reset();
  }

  @Test
  public void not_found() {
    get("/notfound").produces(404, "text/html", "Page not found");
  }

  @Test
  public void content_types() {
    server.configure(routes -> {
      routes.get("/index", "Hello");
      routes.get("/text", new Payload("text/plain", "TEXT"));
      routes.get("/html", new Payload("text/html", "<body>HTML</body>"));
      routes.get("/raw", "RAW".getBytes(UTF_8));
      routes.get("/json", new Person("NAME", 42));
    });

    get("/index").produces("text/html", "Hello");
    get("/text").produces("text/plain", "TEXT");
    get("/html").produces("text/html", "HTML");
    get("/raw").produces("application/octet-stream", "RAW");
    get("/json").produces("application/json", "{\"name\":\"NAME\",\"age\":42}");
  }

  @Test
  public void request_params() {
    server.configure(routes -> {
      routes.get("/hello/:name", (String name) -> "Hello " + name);
      routes.get("/other/:name", (String name) -> "Other " + name);
      routes.get("/say/:what/how/:loud", (String what, String loud) -> what + " " + loud);
      routes.get("/:one/:two/:three", (String one, String two, String three) -> one + " " + two + " " + three);
    });

    get("/hello/Dave").produces("Hello Dave");
    get("/hello/John Doe").produces("Hello John Doe");
    get("/other/Joe").produces("Other Joe");
    get("/say/HI/how/LOUD").produces("HI LOUD");
    get("/ONE/TWO/THREE").produces("ONE TWO THREE");
  }

  @Test
  public void query_params() {
    server.configure(routes -> {
      routes.get("/index", "Hello");
      routes.get("/hello?name=:name", (String name) -> "Hello " + name);
      routes.add(new Object() {
        @Get("/keyValues")
        public String keyValues(Map<String, String> keyValues) {
          return keyValues.toString();
        }
      });
    });

    get("/index?query=useless").produces("Hello");
    get("/hello?name=Dave").produces("Hello Dave");
    get("/keyValues?key1=value1&key2=value2").produces("key2=value2");
  }

  @Test
  public void static_content() {
    get("/").produces("text/html", "Hello From a File");
    get("/index.html").produces("text/html", "Hello From a File");
    get("/test").produces("text/html", "TEST");
    get("/test.html").produces("text/html", "TEST");
    get("/js/script.js").produces("application/javascript", "console.log('Hello');");
    get("/js/script.coffee").produces("application/javascript", "console.log('Hello');");
    get("/assets/style.css").produces("text/css", "* {}");
    get("/assets/style.less").produces("text/css", "body h1 {\n  color: red;\n}");
    get("/assets/style.css.map").produces("text/plain", "\"file\":\"/assets/style.css.css\"");
    get("/hello.md").produces("text/html", "<strong>Hello</strong>");
    get("/goodbye.markdown").produces("text/html", "<strong>Good Bye</strong>");
  }

  @Test
  public void private_files() {
    get("/../private.txt").produces(404);
    get("/_config.yaml").produces(404);
    get("/_layouts/default.html").produces(404);
    get("/unknown").produces(404);
    get("/js").produces(404);
  }

  @Test
  public void annotated_resources() {
    server.configure(routes -> routes.add(new Object() {
      @Get("/hello")
      @Get("/")
      public String hello() {
        return "Hello";
      }

      @Get("/bye/:whom")
      public String bye(String whom) {
        return "Good Bye " + whom;
      }

      @Get("/add/:left/:right")
      public int add(int left, int right) {
        return left + right;
      }
    }));

    get("/hello").produces("Hello");
    get("/").produces("Hello");
    get("/bye/Bob").produces("Good Bye Bob");
    get("/add/22/20").produces("application/json", "42");
  }

  @Test
  public void annotated_resources_with_prefix() {
    server.configure(routes -> routes.add("/say", new Object() {
      @Get("/hello")
      public String hello() {
        return "Hello";
      }
    }));

    get("/say/hello").produces("Hello");
  }

  @Test
  public void io_streams() {
    server.configure(routes -> routes.get("/", () -> new Payload("text/html", new ByteArrayInputStream("Hello".getBytes()))));

    get("/").produces("text/html", "Hello");
  }

  @Test
  public void templates() {
    server.configure(routes -> {
      routes.get("/hello/:name", (String name) -> new Template("1variable.txt").render("name", name));
      routes.get("/bye", () -> new Template("goodbye").render());
      routes.get("/1variable", Model.of("name", "Toto"));
    });

    get("/pageYaml").produces("<div>_PREFIX_TEXT_SUFFIX_</div>");
    get("/hello/Joe").produces("Hello Joe");
    get("/bye").produces("<p><strong>Good Bye</strong></p>");
    get("/1variable").produces("text/html", "Hello Toto");
  }

  @Test
  public void priority_to_route() {
    server.configure(routes -> routes.get("/", "PRIORITY"));

    get("/").produces("PRIORITY");
  }

  @Test
  public void redirect() {
    server.configure(routes -> {
      routes.get("/", Payload.seeOther("/login"));
      routes.get("/login", "LOGIN");
    });

    get("/").produces("LOGIN");
  }

  @Test
  public void filter() {
    server.configure(routes -> {
      routes.get("/", "NOT FILTERED");
      routes.get("/other", "OTHER");
      routes.filter((uri, request, response) -> {
        if ("/".equals(uri)) {
          response.setValue("Content-Type", "text/html");
          response.setContentLength(8);
          response.setCode(200);
          response.getPrintStream().append("FILTERED");
          return true;
        }
        return false;
      });
    });

    get("/").produces("FILTERED");
    get("/other").produces("OTHER");
  }

  @Test
  public void error() {
    server.configure(routes -> routes.get("/", () -> {
      throw new RuntimeException("BUG");
    }));

    get("/").produces(500, "text/html", "An error occurred on the server");
  }

  @Test
  public void supports_spied_resources() {
    server.configure(routes -> routes.add(spy(new TestResource())));

    get("/").produces("HELLO");
  }

  @Test
  public void support_post() {
    server.configure(routes -> {
      routes.post("/post", () -> "Done");
      routes.get("/get", () -> "Done");
      routes.get("/action", () -> "Done GET");
      routes.post("/action", () -> "Done POST");
      routes.post("/post/:who", (String who) -> "Done " + who);
      routes.add(new Object() {
        @Post("/person")
        @Post("/person_alt")
        public String create() {
          return "CREATED";
        }
      });
    });

    post("/post").produces("Done");
    post("/post/Bob").produces("Done Bob");
    post("/action").produces("Done POST");
    get("/action").produces("Done GET");
    post("/person").produces("CREATED");
    post("/get").produces(405);
    post("/index.html").produces(405);
    post("/unknown").produces(404);
  }

  @Test
  public void postForm() {
    server.configure(routes -> {
      routes.post("/postForm", (Context context) -> "CREATED " + context.get("firstName") + " " + context.get("lastName"));
      routes.add(new Object() {
        @Post("/postFormResource")
        public String create(Map<String, String> keyValues) {
          return "CREATED " + keyValues.get("firstName") + " " + keyValues.get("lastName");
        }
      });
      routes.add(new Object() {
        @Post("/postBean")
        public String create(Human human) {
          return "CREATED " + human.firstName + " " + human.lastName;
        }
      });
    });

    post("/postForm", "firstName", "John", "lastName", "Doe").produces("CREATED John Doe");
    post("/postFormResource", "firstName", "Jane", "lastName", "Doe").produces("CREATED Jane Doe");
    post("/postBean", "firstName", "John", "lastName", "Doe").produces("CREATED John Doe");
  }

  @Test
  public void site_variables() {
    get("/testTags").produces("<p>\nscala\n\njava, scala\n</p>\n<p>\nscala\n</p>");
  }

  static class TestResource {
    @Get("/")
    public String hello() {
      return "HELLO";
    }
  }

  static class Person {
    String name;
    int age;

    Person(String name, int age) {
      this.name = name;
      this.age = age;
    }
  }

  static class Human {
    public String firstName;
    public String lastName;
  }
}
