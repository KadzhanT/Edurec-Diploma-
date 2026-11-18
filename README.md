# EduRec: course recommendations on MongoDB

Final project: a **collaborative-filtering recommendation system on a NoSQL database**.
It continues Assignment #6 and keeps the same topic, database and data model. Students browse a course catalog, and every action they take (view, enroll, grade, complete, rate) is stored as an interaction. An **item-based collaborative filtering** engine turns these interactions into personalized course recommendations.

| Layer | Technology |
|---|---|
| Database | MongoDB 8 (Docker) |
| Backend | Kotlin 2.4, Spring Boot 4.1 (Web MVC, Data MongoDB, Security + JWT, Cache, Scheduling) |
| UI | Single page `src/main/resources/static/index.html` (vanilla JS, served by Spring) |
| API docs | springdoc OpenAPI, Swagger UI |
| Tests | JUnit 5, MockK, Testcontainers (real MongoDB) |

## Quick start

Requirements: JDK 21 and Docker.

```bash
docker compose up -d          # MongoDB on localhost:27017
./gradlew bootRun             # Windows: gradlew.bat bootRun
```

The first start seeds an empty database with 40 courses, 202 students and about 7k interactions, then builds the recommendation model.

- UI: <http://localhost:8080>
- Swagger UI: <http://localhost:8080/swagger-ui.html>

| Demo account | Password | Role |
|---|---|---|
| `student@demo.kz` | `student123` | STUDENT (has a history, so gets personal recommendations) |
| `admin@demo.kz` | `admin123` | ADMIN (manages courses, can rebuild the model) |
| `student1..200@demo.kz` | `password123` | synthetic students |

### Configuration (environment variables or `--key=value`)

| Property | Default | Meaning |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/edurec` | database |
| `JWT_SECRET` | dev secret | HS256 key, at least 32 bytes |
| `SEED_ENABLED` / `app.seed.enabled` | `true` | seed an empty database |
| `app.seed.students` | `200` | number of synthetic students |
| `app.recs.rebuild-interval` | `PT10M` | how often the model is recomputed |

> A database created by Assignment #6 is reused as is, and the model is built from its interactions at startup.
> To get the new seed (students have category preferences, which makes the recommendations more meaningful), use an empty database, e.g. `MONGODB_URI=mongodb://localhost:27017/edurec_final`.

## How recommendations work

```
interactions ──$group──▶ student×course preference matrix ──cosine──▶ course_similarities (top-20 per course)
                                                                             │
GET /me/recommendations ── student history (index) + findAllById(similarities) ──▶ Σ sim × preference ──▶ top N (cached)
```

1. **Preference score.** One aggregation (`$group` by student and course) reduces the event log. An explicit rating (1..5) wins. Otherwise the strongest implicit signal is used: VIEW = 1, ENROLL = 3, COMPLETE = 4, GRADE = grade/20.
2. **Item-item cosine similarity.** For every pair of courses taken by the same student, sum the products of the scores, then divide by the vector norms. The top 20 neighbours of each course are stored in the `course_similarities` collection, one document per course (`_id` = courseId). This is a denormalized read model: one lookup by `_id`, no joins.
3. **Scoring.** For a student, every unseen course gets `score = Σ similarity(seen, candidate) × preference(seen)`. The seen course that contributed most becomes the explanation: *"Students who liked X also chose this"*.
4. **Cold start.** If a student has no history, or there are too few candidates, the list is filled with the most popular unseen courses (`stats.enrollCount`, indexed).
5. **Freshness.** The model is rebuilt after seeding, at startup, every 10 minutes (`@Scheduled`) and on demand by an admin. A new interaction evicts that student's cached list, so the next request recomputes it against the current model.

The algorithm is implemented from scratch (`itemSimilarities` and `recommend` in `recommendation/Recommendation.kt`). They are pure functions, so they are unit-tested without a database.

Why item-based and not user-based: the catalog (tens to hundreds of courses) is much smaller and more stable than the student base (thousands). Item neighbourhoods therefore change slowly, can be precomputed, and are small. User-user similarity would grow with the square of the number of students.

## API

New endpoints added to the Assignment #6 API (all under JWT, except the public course endpoints):

| Method | Path | Access | Description |
|---|---|---|---|
| GET | `/api/students/me/recommendations?limit=10` | student | Personalized recommendations with a score and an explanation |
| GET | `/api/courses/{id}/similar?limit=5` | public | "Students who took this also took…" |
| POST | `/api/admin/recommendations/rebuild` | ADMIN | Recompute the model now, returns the statistics |

Existing endpoints:
- `POST /api/auth/register`, `POST /api/auth/login`
- `GET/POST/PUT/DELETE /api/courses` (full-text search, filters)
- `POST /api/interactions`
- `GET /api/students/me/interactions`
- `GET/PUT /api/students/me`

Example:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"student@demo.kz","password":"student123"}' | jq -r .token)
curl -s "localhost:8080/api/students/me/recommendations?limit=5" -H "Authorization: Bearer $TOKEN" | jq
```

## Optimizations

| Technique | Where | Effect |
|---|---|---|
| Precomputed model | `course_similarities` collection | No similarity math per request; the request only reads about 10 small documents by `_id` |
| Compound index `{studentId:1, occurredAt:-1}` | `interactions` | The student history lookup is an index scan instead of a full collection scan |
| Index `{stats.enrollCount:-1}` | `courses` | Popularity fallback without an in-memory sort |
| Cache (`@Cacheable`) | `recs` per student, `similar` per course | Repeated requests are served from memory; evicted per student on a new interaction and fully on rebuild |
| Bulk insert | seeder (`insertMany`) | Seeding 20k students (about 600k events) takes seconds |

Measured results are in [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## Tests

```bash
./gradlew test     # Docker must be running (Testcontainers starts mongo:8)
```

- `RecommendationTest`: similarity and scoring on a hand-made matrix (co-taken course ranks first, seen courses are excluded, empty history gives nothing).
- `ApiFlowIT`: the end-to-end flow against a real MongoDB. It covers auth, CRUD, search and interactions, then rebuilds the model and checks recommendations, similar courses and the admin-only access.

## Performance testing

The benchmark reseeds a **separate** database at 200 / 1,000 / 5,000 / 20,000 students. It refuses to run unless the database name contains `bench`. It then measures four ways of serving recommendations:

| Variant | What is measured |
|---|---|
| A | Baseline: build the CF model from the event log on every request |
| B | Precomputed model, history index dropped (collection scan) |
| C | Precomputed model + index |
| D | C + cache hit |

It also measures rebuild time, `explain()` statistics and the offline quality (leave-one-out hit-rate@10, CF vs most-popular).

```bash
./gradlew bootJar
MONGODB_URI=mongodb://localhost:27017/edurec_bench \
  java -Xmx2g -jar build/libs/Diploma-1.0.0.jar --app.bench.enabled=true --app.seed.enabled=false --server.port=0
# results: build/bench/results.csv
```

Optional: `--app.bench.sizes=200,1000` and `--app.bench.samples=100`.

## Project structure

```
src/main/kotlin/kz/diploma/edurec/
  auth/            registration, login, JWT
  course/          catalog, search, stats
  interaction/     event log (VIEW, ENROLL, RATE, GRADE, COMPLETE)
  student/         profile
  recommendation/  CF engine, API, benchmark
  config/          security, data seeder
src/main/resources/static/index.html   web UI
docs/                                  performance report, final report, presentation
```
