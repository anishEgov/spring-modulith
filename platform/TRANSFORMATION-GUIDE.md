# Spring Modulith POC — Transformation Guide (Standup Update)

## 1. Goal
Evaluate **Spring Modulith** by converting a set of independently-deployed eGov
microservices into a **single modular-monolith application**, where the previous
service-to-service HTTP calls become **in-process calls** while module boundaries are
still **enforced** (by tests, not by the network).

## 2. What Spring Modulith is (one line)
A modular monolith: one deployable, each service becomes a *module* (a Java package),
and a build-time test (`ApplicationModules.verify()`) fails if one module reaches into
another's internals. We keep the boundaries microservices gave us, without the network
cost / ops overhead.

## 3. Which services we picked, and why
We had to pick services that are **already on the same tech baseline**, because a modulith
is one Spring Boot version / one JVM for the whole app.

| Service | Spring Boot | Java | Decision |
|---|---|---|---|
| **individual** | 3.2.2 | 17 | ✅ consumer |
| **idgen** | 3.4.5 | 17 | ✅ provider |
| **localization** | 3.2.2 | 17 | ✅ provider |
| egov-user | **1.5** | **8** | ❌ excluded |

**Why egov-user was excluded:** it would need a 2-major-version Spring Boot upgrade
(1.5 → 3.x), `javax → jakarta` migration, and a full rewrite of its OAuth2 stack
(`spring-security-oauth2` is removed in Spring 6). That upgrade alone is bigger than the
entire rest of this POC, so we deferred it.

**The relationship we exercised:** `individual` calls `idgen` (to generate IDs) and
`localization` (to fetch SMS templates) over HTTP today. Those are the two calls we
collapsed into in-process module calls.

## 4. Changes required for the transformation
This is the part the team should understand — the modulith *concepts* were small; most of
the work was **making three independently-built services coexist in one process**.

### A. New application shell (small)
- One new Maven module `platform/` with **one `pom.xml`** that merges all three services'
  dependencies and adds the **`spring-modulith-bom` + starters**.
- One `@SpringBootApplication` (`org.egov.platform.PlatformApplication`).
- One `ModularityTests` running `ApplicationModules.of(...).verify()` + doc generation.

### B. Repackaging into modules (the biggest mechanical change)
Each service became a module = a first-level package under `org.egov.platform`:
```
org.egov.platform
├── individual/    (was org.egov.individual.*)
├── idgen/         (was org.egov.id.*)
└── localization/  (was org.egov.config/domain/persistence/web.*)
```
- `idgen` and `localization` **squatted on generic packages** (`org.egov`,
  `org.egov.config`, `org.egov.domain`, `org.egov.web`…). Left as-is they collide with
  each other. We renamed every package + import into a module namespace.
- Each service's main class was dropped (one app now).

### C. Converting the HTTP edges to in-process calls (the actual point)
- Added a **public facade** in each provider module's top-level package:
  - `idgen.IdGenApi.generateIds(...)` → returns `List<String>`
  - `localization.LocalizationApi.getMessages(...)` → returns `Map<code,message>`
  - These return **plain types**, so each provider's domain model stays **internal** to its
    module (other modules can't touch it — boundary stays clean).
- Rewired the consumer:
  - `EnrichmentService` now calls `IdGenApi` instead of the HTTP `IdGenService`.
  - `NotificationService` now calls `LocalizationApi` instead of POSTing to localization.
- **Spring Modulith caught a real issue**: our first wiring used field injection, and
  `verify()` failed ("prefer constructor injection for cross-module deps"). Switched to
  constructor injection. This is exactly the boundary enforcement we wanted.

### D. Configuration consolidation (needed to actually boot)
Three services = three `application.properties`, three sets of Spring beans. We merged:
- **One datasource, one Redis, one Kafka** config; **one Flyway** run over all three
  modules' migration folders (`db/migration/{individual,idgen,localization}`).
- **Duplicate bean name** `responseInfoFactory` existed in two modules → gave each a
  distinct bean name.
- Some library beans (`mdms-client`) weren't picked up → added them to the component scan.

### E. Infrastructure to run & test it locally
- `docker-compose.yml`: Postgres + Redis + Kafka.
- `kubectl port-forward` for the services `individual` **still** calls over HTTP
  (user / boundary / mdms / enc) — those were out of POC scope, so they stay external.
- **egov-persister** added to compose to do the real async DB persistence (individual
  produces to Kafka; persister consumes and writes to Postgres — standard eGov pattern).

## 5. Boot-time obstacles we hit and solved (good "war stories" for standup)
1. **Two beans with the same name** across modules → renamed.
2. **mdms-client bean not scanned** (idgen needed it) → added `org.egov.mdms` to scan.
3. **enc-client fetches its security policy from MDMS *at startup*** → MDMS had to be
   reachable before boot, and pointed at a tenant that actually has the policy
   (`dev`; `default` was empty and caused a null-pointer).
4. **idgen needed a format definition** in its table → seeded one row locally.
5. **Kafka advertised-listener** problem for the persister container → configured dual
   listeners (host uses `localhost:9092`, containers use `kafka:29092`).

## 6. Proof it works (end-to-end)
- `ApplicationModules.verify()` is **green** — 3 modules detected, no boundary violations.
- App **boots** in ~11s with all three modules.
- `POST /v1/_create` →
  - `individualId = IND-000004` generated by the **in-process idgen** module
    (**0 HTTP calls** to the old idgen host),
  - SMS `"Dear John, your registration ID is IND-000004"` built from a template fetched by
    the **in-process localization** module (**0 HTTP calls** to the old localization host),
  - record encrypted (enc-client), **persisted by egov-persister**, and returned by
    `POST /v1/_search`.

## 7. What is NOT done (scope/limitations to set expectations)
- **egov-user not included** (upgrade cost — see §3).
- Only the `individual→idgen` and `individual→localization` edges were converted; the other
  downstream calls (`user`, `boundary`, `mdms`, `enc`) are **still HTTP** (out of scope).
- We used **direct in-process calls**, not Spring Modulith's **event-driven** model +
  event publication registry (a possible next step for write-side decoupling).
- Single flattened Maven module (fine for a POC); a multi-module build would be cleaner
  for production.
- Not production-hardened: security/auth not merged, no load testing.

## 8. Effort & key takeaway
- The **modulith mechanics are cheap** (a BOM, a base package, one verify test).
- **~90% of the effort** was: repackaging collisions, consolidating three services' config
  into one bootable context, and getting shared eGov libraries + downstream deps to come up.
- **Version alignment is the gating factor** for *which* services can be combined — that's
  why egov-user is a separate, larger track.

## 9. Appendix — Code change snippets

### 9.1 `pom.xml` — add Spring Modulith (the only "new framework" bit)
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-bom</artifactId>
      <version>1.1.3</version>
      <type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-core</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-test</artifactId>
    <scope>test</scope>
  </dependency>
  <!-- + merged deps from individual, idgen, localization -->
</dependencies>
```

### 9.2 Single application class (component scan widened for shared eGov libs)
```java
@SpringBootApplication
@EnableCaching
@Import(TracerConfiguration.class)
// modules live under org.egov.platform; org.egov.mdms holds mdms-client beans
@ComponentScan(basePackages = {"org.egov.platform", "org.egov.mdms"})
public class PlatformApplication {
    public static void main(String[] args) { SpringApplication.run(PlatformApplication.class, args); }
}
```

### 9.3 The boundary-enforcing test (the heart of Modulith)
```java
class ModularityTests {
    static final ApplicationModules modules = ApplicationModules.of(PlatformApplication.class);

    @Test void verifiesModularStructure() { modules.verify(); }     // fails build on a boundary violation
    @Test void writesDocumentation()      { new Documenter(modules).writeDocumentation(); }
}
```

### 9.4 Provider facade — `idgen` (returns plain types; domain stays internal)
```java
@Service
public class IdGenApi {                       // org.egov.platform.idgen (top-level = public API)
    private final IdGenerationService idGenerationService;   // internal to the module

    public List<String> generateIds(String tenantId, String idName, String format, int count) {
        IdRequest r = new IdRequest();
        r.setIdName(idName); r.setTenantId(tenantId); r.setFormat(format); r.setCount(count);
        IdGenerationRequest req = new IdGenerationRequest();
        req.setRequestInfo(new RequestInfo());
        req.setIdRequests(List.of(r));
        IdGenerationResponse resp = idGenerationService.generateIdResponse(req);
        return resp.getIdResponses().stream().map(IdResponse::getId).toList();
    }
}
```

### 9.5 Provider facade — `localization`
```java
@Service
public class LocalizationApi {                 // org.egov.platform.localization (top-level)
    private final MessageService messageService;            // internal to the module

    public Map<String,String> getMessages(String tenantId, String module, String locale, Set<String> codes) {
        MessageSearchCriteria c = MessageSearchCriteria.builder()
            .tenantId(new Tenant(tenantId)).module(module).locale(locale).codes(codes).build();
        Map<String,String> out = new HashMap<>();
        for (Message m : messageService.getFilteredMessages(c)) out.put(m.getCode(), m.getMessage());
        return out;
    }
}
```

### 9.6 Consumer edge #1 — `individual → idgen`
```java
// BEFORE: HTTP via the shared library client
private final IdGenService idGenService;                                 // org.egov.common.service.IdGenService
List<String> ids = idGenService.getIdList(request.getRequestInfo(), tenantId,
                                          properties.getIndividualId(), null, count);

// AFTER: in-process call into the idgen module
private final IdGenApi idGenApi;                                         // org.egov.platform.idgen.IdGenApi
List<String> ids = idGenApi.generateIds(tenantId, properties.getIndividualId(), null, count);
```

### 9.7 Consumer edge #2 — `individual → localization`
```java
// BEFORE: build a URL and POST to the localization service
uri.append(config.getLocalizationHost()).append(config.getLocalizationContextPath())
   .append(config.getLocalizationSearchEndpoint()).append("?tenantId=" + rootTenantId) ...;
Object result = repository.fetchResult(uri, requestInfoWrapper);          // RestTemplate HTTP
codes    = JsonPath.read(result, ...CODES_JSONPATH);
messages = JsonPath.read(result, ...MSGS_JSONPATH);

// AFTER: in-process call into the localization module
Map<String,String> map = localizationApi.getMessages(rootTenantId, module, locale, null);
localizedMessageMap.put(locale + "|" + rootTenantId, map);
```

### 9.8 Constructor injection — *required* by Modulith for cross-module deps
```java
// verify() FAILED with field injection:
//   "Module Individual uses field injection ... Prefer constructor injection instead!"
@Autowired private LocalizationApi localizationApi;          // ❌ rejected by verify()

// FIX:
private final LocalizationApi localizationApi;               // ✅
public NotificationService(LocalizationApi localizationApi) { this.localizationApi = localizationApi; }
```

### 9.9 Bean-name collision fix (two modules, same default bean name)
```java
// idgen
@Service("idgenResponseInfoFactory")          // was @Service
public class ResponseInfoFactory { ... }
// localization
@Component("localizationResponseInfoFactory") // was @Component
public class ResponseInfoFactory { ... }
```

### 9.10 Merged config highlights (`application.properties`)
```properties
# one datasource / redis / kafka for the whole app
spring.datasource.url=jdbc:postgresql://localhost:5433/platform
# one Flyway run over all three modules' migrations
spring.flyway.locations=classpath:/db/migration/idgen,classpath:/db/migration/localization,classpath:/db/migration/individual
# enc-client loads its DataSecurity policy from MDMS at boot for this tenant
state.level.tenant.id=dev
```

### 9.11 egov-persister + Kafka dual listeners (`docker-compose.yml`)
```yaml
kafka:
  environment:
    # host JVM uses localhost:9092 ; containers (persister) use kafka:29092
    KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:29092,EXTERNAL://localhost:9092
persister:
  image: egovio/egov-persister:master-3b238aa
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/platform
    KAFKA_CONFIG_BOOTSTRAP_SERVER_CONFIG: kafka:29092
    EGOV_PERSIST_YML_REPO_PATH: file:///configs/individual-persister.yml
```

## 10. Suggested next steps
1. Convert one write-side edge to a **Spring Modulith event** (publish/subscribe) to show
   in-process decoupling with the event publication registry (retry/replay).
2. Decide the **egov-user upgrade** as its own story (Boot 3 + Spring Authorization Server
   or external IdP) if user is to join the modulith.
3. Add `@ApplicationModuleTest` slices per module for faster, isolated module tests.

---

# 11. Event-driven step — async persistence via a Modulith event

This section is the running log of the second transformation: from **in-process facade
calls** to **event-driven communication**, and the questions that shaped it.

## 11.1 Q&A that shaped this step

### Q1. Does `verify()` actually enforce boundaries, or is it decorative? Can an API call test it?
**An API call cannot test it.** `ApplicationModules.verify()`
(`ModularityTests.verifiesModularStructure`) is **static bytecode analysis** at test time
(ArchUnit under the hood) — it never boots a server or runs code. It checks two things:
1. No module references another module's **non-exposed** (sub-package) types.
2. No **cyclic** dependencies between modules.

An HTTP call only proves the endpoint runs — orthogonal to boundary enforcement.

**How we proved `verify()` is real:** injected a deliberate violation — made
`individual`'s `EnrichmentService` import `idgen`'s internal
`org.egov.platform.idgen.service.IdGenerationService` (bypassing the `IdGenApi` facade).
The test failed with exactly:
```
org.springframework.modulith.core.Violations:
- Module 'individual' depends on non-exposed type
  org.egov.platform.idgen.service.IdGenerationService within module 'idgen'!
```
Reverting → `BUILD SUCCESS`. The red→green flip is the proof: the facades are the only
legal entry points because the real logic lives in non-exposed sub-packages.

**Build gotcha:** project targets Java 17 but the machine default JDK is 25; Lombok
1.18.22 can't run its annotation processor on JDK 25, so a *clean recompile* fails. Pin
`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` (or bump Lombok ≥ 1.18.32).

### Q2. "Modulith is nothing without events." Convert the `IdGenApi`/`LocalizationApi` calls to events.
**Those two are the wrong candidates.** Modulith events (`@ApplicationModuleListener`) are
**fire-and-forget** — the publisher gets nothing back. But both facade calls are
**synchronous queries that need a return value inline**:
- `IdGenApi.generateIds(...)` → IDs are stamped onto individuals **before they are saved**.
- `LocalizationApi.getMessages(...)` → template is needed right then to build the SMS.

Forcing these onto events needs a blocking request/reply hack — worse than the facade.
**Queries stay as facade calls; events are for decoupled side-effects.**

### Q3. To *test* an async event we need an observable side-effect — Notification or Persister?
**Persister.** An async event only proves something if we can see its effect.

| Candidate | Observable locally? | Verdict |
|---|---|---|
| Notification (SMS) | ✗ needs an SMS gateway credential; silently no-ops | rejected |
| Persister (DB write) | ✓ Postgres already in `docker-compose.yml` (`localhost:5433`) | **chosen** |

## 11.2 The change

**Before:** `IndividualService.create` → `individualRepository.save(..., save-individual-topic)`
pushes to **Kafka**; the **external `egov-persister` container** consumes it and writes the
`individual` table. Async, but across a process + a broker.

**After:** the Kafka hop becomes an **in-process Spring Modulith application event**:
```
individual module                         persistence module (new)
-----------------                         ------------------------
IndividualService.create()
  publishes IndividualCreatedEvent  ──▶   @ApplicationModuleListener (async, after-commit)
  (exposed type, root package)            IndividualPersistenceListener
                                            └─ INSERT INTO individual (...)  [local Postgres]
```

Pieces:
1. `spring-modulith-starter-jpa` — the **event publication registry** (`event_publication`
   table). Makes events durable / at-least-once / retryable, and is our **proof**: a
   completed row there == the async listener ran to completion.
2. `org.egov.platform.individual.IndividualCreatedEvent` — exposed event type (root package)
   so the `persistence` module may legally depend on it.
3. `org.egov.platform.persistence` — **new module**, `IndividualPersistenceListener`
   (`@ApplicationModuleListener`) inserts into `individual`.
4. `IndividualService.create` publishes the event via `ApplicationEventPublisher`.

> `@ApplicationModuleListener` = `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` +
> `@Transactional`: the listener runs on a different thread, only after the publisher's
> transaction commits — the async decoupling a broker gave us, but in-process.

## 11.3 Files changed
- `pom.xml` — added `spring-modulith-starter-jdbc` (event publication registry).
- `application.properties` — `spring.modulith.events.jdbc.schema-initialization.enabled=true`
  and `republish-outstanding-events-on-restart=true`.
- `PlatformApplication` — `@EnableAsync` (so `@ApplicationModuleListener`'s `@Async` takes effect).
- `individual/IndividualCreatedEvent.java` — **new** exposed event type.
- `persistence/` — **new module**: `IndividualPersistenceListener` + `package-info.java`.
- `individual/service/IndividualService.java` — publishes the event after save.
- `test/.../persistence/PersistenceModuleTests.java` — **new** verification test.

## 11.4 How to run / verify

> Build note: pin JDK 17 — `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.

**Structural boundaries still hold** (the new `persistence` module is a legal citizen):
```
mvn -Dtest=ModularityTests test          # 3 tests green; persistence module recognized
```

**The async event actually persists** (needs docker-compose Postgres up on :5433):
```
docker compose up -d postgres
mvn -Dtest=PersistenceModuleTests test    # green
```
The test publishes `IndividualCreatedEvent` in a transaction and polls for the DB row.

**What it proved** (observed output):
- Listener ran on a **different thread** (`task-1`, not `main`) → genuinely async:
  ```
  [persistence] received IndividualCreatedEvent with 1 individual(s) on thread task-1
  [persistence] persisted individual id=modulith-test-…
  ```
- Row landed in Postgres:
  ```
  SELECT id, givenname, individualid FROM individual WHERE id LIKE 'modulith-test-%';
  → Test | IND-TEST
  ```
- The registry tracked the event and marked it **completed** — this is the durability /
  at-least-once guarantee that makes modulith events broker-like:
  ```
  SELECT event_type, listener_id, completion_date IS NOT NULL AS completed FROM event_publication;
  → IndividualCreatedEvent | …IndividualPersistenceListener.on(…) | t
  ```

## 11.6 Proper test: in-process module only + durability

Two follow-up questions were checked.

### Stop the external persister so only the module writes
```
docker compose stop persister      # postgres/redis/kafka stay up
```
Now the in-process `@ApplicationModuleListener` is the only thing that can write
`individual`. `PersistenceModuleTests.publishingEventInsertsExactlyOneRow` captures the
row count before/after and asserts +1:
```
[before/after] individual rows: 1 -> 2     # written by the module, not egov-persister
```
(The test then deletes its own row, so the table returns to baseline.)

### Does the event persist if the receiver can't consume it?  **Yes.**
This is the broker-like guarantee. Spring Modulith writes every published event to
`event_publication` **at publish time**, and only stamps `completion_date` when the
listener **succeeds**. `failedDeliveryKeepsEventInRegistryForRetry` forces the listener to
throw (publishes a row with `id = null`, violating the NOT-NULL primary key). Result —
the event is **retained, incomplete, with its full payload**:
```
[durability] retained incomplete event payload:
{"individuals":[{"id":null,"tenantId":"dev",…,"individualId":"DURABILITY-…"}]}
```
- `completion_date IS NULL` → never marked complete (receiver could not consume it).
- the serialized payload is intact in the DB → nothing is lost.
- with `spring.modulith.events.republish-outstanding-events-on-restart=true`, such
  incomplete publications are **redelivered to the listener on the next app start**
  (at-least-once). They persist in the table until processed (or purged).

So an in-process modulith event gives the same "don't lose it if the consumer is down"
property a message broker gives — backed by the `event_publication` table instead of Kafka.

## 11.5 Caveat worth remembering
`@ApplicationModuleListener` is `AFTER_COMMIT`, so **delivery requires the publish to run
inside a transaction**. The test uses a `TransactionTemplate`; in production
`IndividualService.create` would need a transactional boundary for the event to be
delivered (otherwise the after-commit listener never fires). The verification test isolates
the `persistence` module with its own minimal `@SpringBootApplication` test config, because
`PlatformApplication`'s broad `@ComponentScan` otherwise defeats `@ApplicationModuleTest`
slicing and drags in every module's beans (e.g. localization's Redis config).

