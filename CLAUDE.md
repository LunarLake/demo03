# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
.\mvnw spring-boot:run      # PowerShell
.\mvnw test                  # Run tests (95 tests, JaCoCo line coverage ~86%)
.\mvnw clean compile         # Compile only
```

Coverage report: `target/site/jacoco/index.html` (JaCoCo plugin bound to test phase).

### Testing conventions

- Service tests use Mockito mocks + `ReflectionTestUtils.setField(service, "baseMapper", mapper)` (ServiceImpl's parent-class field is not injectable via `@InjectMocks`).
- MyBatis-Plus 3.5.15 `BaseMapper` has overloaded `insert(T)`/`insert(Collection<T>)` etc. — always use typed matchers: `any(Reservation.class)`, never bare `any()`.
- Standalone MockMvc (`MockMvcBuilders.standaloneSetup`) needs an `InternalResourceViewResolver` (prefix `/WEB-INF/views/`, suffix `.html`) to avoid "Circular view path" errors.
- Shared stubs in `@BeforeEach` used by only some tests must be `lenient().when(...)` (Mockito strict stubs).
- `LambdaQueryChainWrapper.count()` returns `Long`; `AttendanceRecordService.countTodayCheckIn()` returns `int`.

MySQL `db02`。数据库账号密码勿写入仓库：复制 `src/main/resources/application-local.yml.example` 为 `application-local.yml` 填写，或设置环境变量 `DB_USERNAME` / `DB_PASSWORD`（以及可选的 `DB_HOST` / `DB_PORT` / `DB_NAME`）。

## Architecture

Spring Boot 3.5.14 + Java 17 + Maven, server-side rendered with Thymeleaf. Persistence via MyBatis-Plus 3.5.15 on MySQL `db02`. No Spring Security — Session-based auth via interceptors.

### Layered structure (strict MVC)

```
controller → service (interface) → service/impl → mapper (MyBatis-Plus BaseMapper) → MySQL
```

- **No DTO/VO layer** — entities are used directly in Thymeleaf views.
- **No XML mapper files** — custom SQL uses `@Select` annotations on mapper interfaces.
- **Constructor injection** — mix of `@RequiredArgsConstructor` (services) and explicit constructors (controllers/interceptors).
- **Entities**: Lombok `@Data` + MyBatis-Plus `@TableName`/`@TableId(type=IdType.AUTO)`.

### Entities & DB column mappings

| Entity             | Table                 | Fields (Java → DB)                                                                                                                   |
| ------------------ | --------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `User`             | `t_user`              | id, username, password, name, role(STUDENT/TEACHER/ADMIN), email                                                                     |
| `MeetingRoom`      | `t_meeting_room`      | id, roomName, capacity, equipment, roomStatus(0正常/1维护)                                                                           |
| `Reservation`      | `t_reservation`       | id, userId, roomId, startTime, endTime, reservationStatus(0待审/1通过/2拒绝/3被覆盖/4用户取消/5超时释放), checkInCode, attendeeCount |
| `AttendanceRecord` | `t_attendance_record` | id, reservationId, userId, checkInTime, attendStatus, ip                                                                             |
| `Log`              | `t_log`               | id, username, url, ip, timestamp                                                                                                     |

`AttendanceRecord.attendStatus` maps to column `attend_status` via default camelCase→snake_case conversion. Similarly `Reservation.reservationStatus` → `reservation_status` and `MeetingRoom.roomStatus` → `room_status`.

### Controller endpoints

| Controller                | Endpoint                       | Method | Role  | Purpose                                                                                          |
| ------------------------- | ------------------------------ | ------ | ----- | ------------------------------------------------------------------------------------------------ |
| **MainController**        | `/`, `/index`                  | GET    | any   | Home dashboard: room count + my-reservation count + pending count + today check-in               |
|                           | `/login`                       | GET    | —     | Login page (captcha via `/verityImg`)                                                            |
|                           | `/loginAction`                 | POST   | —     | Authenticate, regenerate session, set session attrs                                              |
|                           | `/register`                    | GET    | —     | Registration page                                                                                |
|                           | `/registerAction`              | POST   | —     | Create user, catch duplicate username (role forced to STUDENT)                                   |
|                           | `/logout`                      | GET    | any   | Invalidate session                                                                               |
|                           | `/verityImg`                   | GET    | —     | Generate captcha image (Hutool LineCaptcha)                                                      |
| **RoomController**        | `/rooms`                       | GET    | any   | Room list with keyword search                                                                    |
|                           | `/room/add`                    | POST   | ADMIN | Add meeting room                                                                                 |
|                           | `/room/update`                 | POST   | ADMIN | Update room info                                                                                 |
|                           | `/room/delete/{id}`            | POST   | ADMIN | Delete room (checks for active reservations first)                                               |
| **ReservationController** | `/reservation/apply`           | POST   | any   | Submit reservation (student→status=0, teacher→status=1); validates attendeeCount ≤ room capacity |
|                           | `/my-reservations`             | GET    | any   | User's reservations with attend_status                                                           |
|                           | `/reservation/approve-list`    | GET    | ADMIN | Pending approvals (status=0)                                                                     |
|                           | `/reservation/approve`         | POST   | ADMIN | Approve → status=1 + generate 4-digit code                                                       |
|                           | `/reservation/reject`          | POST   | ADMIN | Reject → status=2                                                                                |
|                           | `/reservation/new`             | GET    | any   | ECharts timeline page; validates room exists + roomStatus=0 before rendering                     |
|                           | `/api/room-schedule`           | GET    | any   | JSON: approved reservations (start_time, end_time, userName, role) for room+date                 |
|                           | `/reservation/cancel/{id}`     | POST   | any   | Cancel own reservation → status=4 (only status 0/1, not if checked in)                           |
|                           | `/reservation/check-in/{id}`   | GET    | any   | Check-in code entry page                                                                         |
|                           | `/reservation/check-in-action` | POST   | any   | Verify code + time window (start-10min ~ start+15min), update attend_status=1                    |
|                           | `/reservation/detail/{id}`     | GET    | ADMIN | Reservation + attendance detail view                                                             |
| **LogController**         | `/admin/logs`                  | GET    | ADMIN | System access log list                                                                           |
|                           | `/admin/dashboard`             | GET    | ADMIN | ECharts dashboard page                                                                           |
|                           | `/api/report-room-usage`       | GET    | ADMIN | JSON: per-room reservation count                                                                 |
|                           | `/api/report-busy-hours`       | GET    | ADMIN | JSON: per-hour reservation count                                                                 |
|                           | `/api/report-attendance-rate`  | GET    | ADMIN | JSON: attended vs absent count                                                                   |

### Session attributes (set by loginAction)

| Key          | Type   | Used by                                                                                                       |
| ------------ | ------ | ------------------------------------------------------------------------------------------------------------- |
| `username`   | String | RoleInterceptor (auth check), LogInterceptor (log record)                                                     |
| `Id`         | Long   | ReservationController, check-in flow, home page count                                                         |
| `name`       | String | header.html, home.html greeting                                                                               |
| `role`       | String | sidebar (menu visibility), role constants in `common/RoleConstant`, Gantt chart (role-based color + override) |
| `email`      | String | (stored, not displayed)                                                                                       |
| `verityCode` | String | loginAction (captcha validation, removed after check)                                                         |

### Interceptors (registered in WebConfig)

- **RoleInterceptor** — checks `session.username != null`, redirects to `/login`.
- **LogInterceptor** — records every request to `t_log` with username (or "匿名"), IP, URL, timestamp. Writes asynchronously via `LogService.saveAsync()`.
- **AdminInterceptor** — checks `session.role == "ADMIN"`, redirects to `/` if not. Covers `/room/add`, `/room/update`, `/room/delete/**`, `/reservation/approve`, `/reservation/approve-list`, `/reservation/reject`, `/reservation/detail/**`, `/admin/**`, `/api/report-*` (room management + approval management + dashboard + logs).
- **CsrfInterceptor** — Synchronizer Token: every session holds a `_csrf` token (exposed as request attribute `_csrf` for templates). All POST requests must carry it via form param `_csrf` or header `X-CSRF-TOKEN`, otherwise redirected to `/`. Registered for `/**` minus static resources (covers login/register forms too). Session cookie uses `SameSite=Lax` (application.yml) as second layer.
- **Excluded paths** (RoleInterceptor + LogInterceptor): `/verityImg`, `/error`, `/css/**`, `/js/**`, `/images/**`, `/**/*.{css,js,jpg,png,gif,ico}`
- **RoleInterceptor additional excludes**: `/login`, `/loginAction`, `/register`, `/registerAction`
- **AdminInterceptor** uses `addPathPatterns` only (no excludes needed).

### Core business logic

**Password hashing**: BCrypt via Hutool `cn.hutool.crypto.digest.BCrypt`. `UserMapper.findByUsername()` looks up user by username only (no password in SQL). `UserServiceImpl.login()` uses `BCrypt.checkpw()` to verify. `UserServiceImpl.register()` **forces `role=STUDENT`** (blocks forged `role=TEACHER/ADMIN` registration) and calls `BCrypt.hashpw()` before `super.save()`. TEACHER/ADMIN accounts are created manually via `sql/init-admin.sql`. Existing plaintext passwords in DB must be re-hashed manually after first deploy.

**Input validation**: JSR-380 annotations on entities (`@NotNull` on roomId/startTime/endTime, `@Positive` on attendeeCount, `@NotBlank` on username/password/name). Controllers use `@Valid` + `BindingResult`. Requires `spring-boot-starter-validation` in pom.xml.

**Conflict detection** (ReservationMapper.findConflicting): checks `r.start_time < #{endTime} AND r.end_time > #{startTime}` for same room with reservation_status 0 or 1. Detects ALL conflicts (student + teacher).

**Teacher override**: if teacher's reservation conflicts with student-only reservations, those students are set to status=3. If any conflict is from another teacher → returns "conflict" (teacher can't override teacher). Teacher's own reservation is auto-approved (status=1). The entire operation is `@Transactional`. Role check via batch `userService.listByIds()`. ADMIN is treated as a student here (no auto-approve, no override) — admin reservations go through the normal approval flow.

**Brute-force protection**: `LoginAttemptService` counts login failures per "username|IP" in memory — 5 consecutive failures lock the account for 15 minutes (reset on successful login). Check-in code entry is limited to 5 wrong attempts per reservation (`too_many_attempts`), counter cleared on success or window expiry. Registration requires the same captcha as login (`/verityImg`, session `verityCode`).

**Check-in flow**: admin approval generates 4-digit `SecureRandom` code. User visits `/reservation/check-in/{id}`, enters code. Validates reservation.reservationStatus==1, reservation.userId ownership (explicit), code match, and time window (startTime-10min ~ startTime+15min). Returns `too_early` or `expired` outside window. Prevents duplicate check-in. `@Transactional`.

**Capacity check**: `apply()` validates `attendeeCount` ≤ `MeetingRoom.capacity`. Returns `over_capacity` if exceeded. Also rejects `startTime < now` → returns `past_time`.

**Room maintenance gate**: `/reservation/new` validates room exists and `roomStatus == 0` before rendering. `rooms.html` shows greyed-out "维护中" button for rooms with status=1. Two-layer defense against users accessing reservation page for rooms under maintenance.

**User cancel**: `POST /reservation/cancel/{id}` sets reservationStatus=4. Allowed only for status 0 or 1, and only if not already checked in (attendStatus != 1).

**Reject guard**: `reject()` only rejects status=0 (pending) reservations. Cannot reject already-approved or already-rejected.

**Zombie release**: `ReservationScheduler` runs every 60s via `@Scheduled`. Finds reservations where status=1 AND now > startTime+15min AND no check-in. Uses conditional `lambdaUpdate().eq(status,1).set(status,5)` to prevent overwriting in-flight status changes.

**Room delete**: `MeetingRoomServiceImpl.deleteRoom()` — `@Transactional`, checks active reservations (status 0/1) via `ReservationMapper.selectCount()`, then `removeById()`. Returns `"has_active:N"` or `"success"`.

**Async logging**: `@EnableAsync` on `Demo03Application`. `LogInterceptor` calls `LogService.saveAsync()` (`@Async`) instead of synchronous `save()`.

**Home page counts**: `MainController.home()` passes `rooms` (all rooms), `todayCheckIn` (today's check-in count), `myReservationCount` (user's own reservations via `lambdaQuery().eq(userId).count()`), and `pendingCount` (status=0 reservations via `lambdaQuery().eq(status,0).count()`).

**Visual timeline (Gantt chart)**: `/reservation/new?roomId=X` shows ECharts custom time-axis chart (8:00-22:00). Room name displayed as readonly text (no dropdown). Date change auto-loads schedule. Click on chart to select start/end time, snaps to 15-min intervals. Form submits to `/reservation/apply`. `/api/room-schedule` returns JSON with `start_time`, `end_time`, `userName`, `role` fields. JS in `static/js/reservation-new.js` (external to avoid Thymeleaf parsing).

**Gantt role-based colors**: teacher reservations = red (`#dc3545`), student reservations = orange (`#fd7e14`). Both roles see color differentiation. Teachers can click on student (orange) zones to override — click handler allows it, backend `apply()` handles the override. Student zones and teacher zones block clicks for non-teachers. Selected time that overlaps student reservations triggers button to change to orange "确认覆盖并提交". Frontend guards: selected range crossing teacher reservation → button disabled red "所选时段与教师预约冲突"; selected range crossing student reservation (for student users) → button disabled red "所选时段与学生预约冲突". `currentUserRole` is injected via `<script th:inline="javascript">` from `${session.role}`.

**Scheduling**: `@EnableScheduling` + `@EnableAsync` on `Demo03Application`. `ReservationScheduler` (`task/` package) runs `@Scheduled(fixedDelay=60_000)`. Log writes use `@Async`.

**API response envelope**: all `/api/*` JSON endpoints return `ApiResponse<T>` record `{ success, data, message }` (`common/ApiResponse`). `GlobalExceptionHandler` (`@RestControllerAdvice`) returns `ApiResponse.fail(...)` for `/api/*` errors and redirects page requests to `/` (full stack logged server-side). Frontend JS must unwrap via `res.data.success` / `res.data.data`.

### Thymeleaf pitfalls

- **Don't nest `${}` in `th:text`**: use pipe literals `th:text="|${r.roomName} (容纳 ${r.capacity} 人)|"` instead of `th:text="${r.roomName} + ' (容纳 ' + ${r.capacity} + ' 人)'"`.
- **Complex JS → external file**: inline `<script>` can trip Thymeleaf parser. Put JS in `src/main/resources/static/js/` and load via `<script src="/js/file.js">`. Static files bypass Thymeleaf entirely.
- **`th:inline="javascript"` required for `/*[[...]]*/`**: without explicit `th:inline="javascript"` on the `<script>` tag, Thymeleaf ignores `/*[[...]]*/` expressions and they render as literal JS comments. Always declare `th:inline="javascript"` when injecting server-side values into JS via this syntax.

### Thymeleaf note for Map results

Custom SQL returning `List<Map<String, Object>>` uses raw column names as Map keys (e.g., `start_time`, `end_time`, `check_in_code`, `attend_status`, `reservation_status`, `attendee_count`). Access underscored keys with bracket notation `r['reservation_status']`, `r['start_time']`, `r['attendee_count']` in templates. CamelCase aliases from `AS` (e.g., `roomName`, `userName`, `role`) work with dot notation.
