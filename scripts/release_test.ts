import $ from "@david/dax"
import { assertEquals, assertRejects, assertThrows } from "@std/assert"
import {
  command,
  publishRelease,
  readNotes,
  readVersion,
  validateTag,
  verifyApk,
  verifyTag,
} from "./release.ts"

const source =
  'applicationId = "dev.scarf.gc"\nversionCode = 6\nversionName = "0.1.1"\n'
const version = readVersion(source)
const ok = (stdout = "") => ({ code: 0, stdout, stderr: "" })
const mock = (
  respond: (program: string, args: string[]) => Promise<ReturnType<typeof ok>>,
): typeof command =>
(program, args) =>
  $`${[program, ...args]}`.clearEnv().quiet().registerCommand(
    program,
    async (context) => {
      const result = await respond(program, context.args)
      await context.stdout.writeText(result.stdout)
      await context.stderr.writeText(result.stderr)
      return { code: result.code }
    },
  )

Deno.test("dax preserves literal command arguments and propagates failures", async () => {
  await Deno.mkdir("build/release-tests", { recursive: true })
  const root = await Deno.makeTempDir({ dir: "build/release-tests" })
  try {
    const payload = `$(touch ${root}/injected); & spaces`
    const result = await command("git", ["rev-parse", "--sq-quote", payload])
    assertEquals(result.stdout.trim(), `'${payload}'`)
    await assertRejects(
      () => Deno.stat(`${root}/injected`),
      Deno.errors.NotFound,
    )
    await assertRejects(() => command("git", ["not-a-git-command"]).text())
  } finally {
    await Deno.remove(root, { recursive: true })
  }
})

Deno.test("Gradle metadata requires unique literal values", () => {
  assertEquals(version, {
    applicationId: "dev.scarf.gc",
    versionCode: "6",
    versionName: "0.1.1",
  })
  for (
    const invalid of [
      "",
      source + source,
      source.replace("6", "0"),
      source.replace("6", "nextCode"),
    ]
  ) {
    assertThrows(() => readVersion(invalid))
  }
})

Deno.test("tags must match the Android version and reject shell syntax", () => {
  validateTag("v0.1.1", version)
  validateTag("v0.1.1-rc.1", { ...version, versionName: "0.1.1-rc.1" })
  for (
    const tag of [
      "main",
      "0.1.1",
      "v0.1.2",
      "v0.1.1$(id)",
      "v0.1.1`id`",
      "v0.1.1\n",
    ]
  ) {
    assertThrows(() => validateTag(tag, version))
  }
  assertThrows(() =>
    validateTag("v0.1.1$(id)", { ...version, versionName: "0.1.1$(id)" })
  )
})

Deno.test("tag verification requires an existing tag at HEAD", async () => {
  await assertRejects(() =>
    verifyTag(
      "v0.1.1",
      version,
      mock(() =>
        Promise.resolve({ code: 1, stdout: "", stderr: "missing tag" })
      ),
    )
  )
  await assertRejects(() =>
    verifyTag(
      "v0.1.1",
      version,
      mock((_program, args) =>
        Promise.resolve(ok(args[1] === "HEAD" ? "head" : "different"))
      ),
    )
  )
  await verifyTag("v0.1.1", version, mock(() => Promise.resolve(ok("head"))))
})

Deno.test("lightweight and annotated tags resolve to the checkout commit", async () => {
  await Deno.mkdir("build/release-tests", { recursive: true })
  const root = await Deno.makeTempDir({ dir: "build/release-tests" })
  try {
    const run: typeof command = (_program, args) =>
      $`${[Deno.env.get("GIT_PATH")!, "-C", root, ...args]}`.clearEnv()
        .env({ PATH: Deno.env.get("PATH") }).quiet()
    assertEquals((await run("git", ["init", "-q"])).code, 0)
    assertEquals(
      (await run("git", [
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.invalid",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "--allow-empty",
        "-m",
        "fixture",
      ])).code,
      0,
    )
    await run("git", ["tag", "v0.1.1"])
    await verifyTag("v0.1.1", version, run)
    await run("git", [
      "-c",
      "user.name=Test",
      "-c",
      "user.email=test@example.invalid",
      "-c",
      "tag.gpgsign=false",
      "tag",
      "-a",
      "v0.1.2",
      "-m",
      "fixture",
    ])
    await verifyTag("v0.1.2", { ...version, versionName: "0.1.2" }, run)
    await assertRejects(() =>
      verifyTag("v0.1.3", { ...version, versionName: "0.1.3" }, run)
    )
  } finally {
    await Deno.remove(root, { recursive: true })
  }
})

Deno.test("APK application ID, code and name must all match", () => {
  const badging =
    "package: name='dev.scarf.gc' versionCode='6' versionName='0.1.1' platformBuildVersionName='15'\n"
  verifyApk(badging, version)
  for (
    const [from, to] of [["dev.scarf.gc", "other.app"], ["'6'", "'5'"], [
      "0.1.1",
      "0.1.2",
    ]]
  ) {
    assertThrows(() => verifyApk(badging.replace(from, to), version))
  }
  assertThrows(() => verifyApk("", version))
})

Deno.test("current repository changelogs exist for every locale", async () => {
  const actualVersion = readVersion(
    await Deno.readTextFile("app/build.gradle.kts"),
  )
  assertEquals(
    await readNotes(".", actualVersion.versionCode),
    await Deno.readTextFile(
      `fastlane/metadata/android/en-US/changelogs/${actualVersion.versionCode}.txt`,
    ),
  )
})

Deno.test("missing or blank localized changelogs block publication", async () => {
  await Deno.mkdir("build/release-tests", { recursive: true })
  const root = await Deno.makeTempDir({ dir: "build/release-tests" })
  const metadata = `${root}/fastlane/metadata/android`
  try {
    await Deno.mkdir(`${metadata}/en-US/changelogs`, { recursive: true })
    await Deno.writeTextFile(
      `${metadata}/en-US/changelogs/6.txt`,
      "English notes.\n",
    )
    await Deno.mkdir(`${metadata}/ko/changelogs`, { recursive: true })
    await assertRejects(() => readNotes(root, "6"))
    await Deno.writeTextFile(`${metadata}/ko/changelogs/6.txt`, "  \n")
    await assertRejects(() => readNotes(root, "6"))
    await Deno.writeTextFile(
      `${metadata}/ko/changelogs/6.txt`,
      "한국어 변경 내역.\n",
    )
    assertEquals(await readNotes(root, "6"), "English notes.\n")
    await assertRejects(() => readNotes(root, "5"))
    await Deno.remove(`${metadata}/en-US`, { recursive: true })
    await assertRejects(() => readNotes(root, "6"))
  } finally {
    await Deno.remove(root, { recursive: true })
  }
})

const options = {
  tag: "v0.1.1",
  apk: "gc-0.1.1.apk",
  notes: "build/release-notes.txt",
  repository: "scarf005/gc",
}

Deno.test("new releases use changelog notes and require a remote tag", async () => {
  const calls: string[][] = []
  await publishRelease(
    options,
    mock((_program, args) => {
      calls.push(args)
      return Promise.resolve(
        args[0] === "api"
          ? { code: 1, stdout: "", stderr: "gh: Not Found (HTTP 404)" }
          : ok(),
      )
    }),
  )
  assertEquals(calls, [
    ["api", "repos/scarf005/gc/releases/tags/v0.1.1"],
    [
      "release",
      "create",
      "v0.1.1",
      "gc-0.1.1.apk",
      "--verify-tag",
      "--title",
      "v0.1.1",
      "--notes-file",
      "build/release-notes.txt",
    ],
  ])
})

Deno.test("reruns replace placeholders but preserve human-authored notes", async () => {
  for (
    const body of [
      null,
      "",
      "  \n",
      "Release v0.1.1",
      "Release v0.1.1\n",
      "Release v0.1.1 ",
      "    Release v0.1.1",
      "Hand-written release notes.",
    ]
  ) {
    const calls: string[][] = []
    await publishRelease(
      options,
      mock((_program, args) => {
        calls.push(args)
        return Promise.resolve(
          ok(args[0] === "api" ? JSON.stringify({ body }) : ""),
        )
      }),
    )
    assertEquals(calls[1], [
      "release",
      "upload",
      "v0.1.1",
      "gc-0.1.1.apk",
      "--clobber",
    ])
    const replace = body === null || body.trim() === "" ||
      body === "Release v0.1.1"
    assertEquals(calls.length, replace ? 3 : 2)
    if (calls.length === 3) {
      assertEquals(calls[2], [
        "release",
        "edit",
        "v0.1.1",
        "--notes-file",
        "build/release-notes.txt",
      ])
    }
  }
})

Deno.test("malformed release responses stop before any upload or edit", async () => {
  for (
    const response of [
      "invalid JSON",
      "null",
      "[]",
      "{}",
      '{"body":42}',
      '{"body":{}}',
    ]
  ) {
    let calls = 0
    await assertRejects(() =>
      publishRelease(
        options,
        mock(() => {
          calls++
          return Promise.resolve(ok(response))
        }),
      )
    )
    assertEquals(calls, 1)
  }
})

Deno.test("API and upload failures never fall through to release creation or editing", async () => {
  for (const error of ["HTTP 401", "HTTP 403", "HTTP 500", "network failure"]) {
    let calls = 0
    await assertRejects(() =>
      publishRelease(
        options,
        mock(() => {
          calls++
          return Promise.resolve({ code: 1, stdout: "", stderr: error })
        }),
      )
    )
    assertEquals(calls, 1)
  }
  let calls = 0
  await assertRejects(() =>
    publishRelease(
      options,
      mock(() => {
        calls++
        return Promise.resolve(
          calls === 1
            ? ok(JSON.stringify({ body: "Release v0.1.1" }))
            : { code: 1, stdout: "", stderr: "upload failure" },
        )
      }),
    )
  )
  assertEquals(calls, 2)
})
