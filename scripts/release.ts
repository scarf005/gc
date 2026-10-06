import $ from "@david/dax"
import { fromFileUrl, join } from "@std/path"
import { parse } from "@std/semver"
import * as v from "@valibot/valibot"

export function readVersion(source: string) {
  const field = (name: string, pattern: string) => {
    const matches = [
      ...source.matchAll(new RegExp(`^\\s*${name} = ${pattern}\\s*$`, "gm")),
    ]
    if (matches.length !== 1) throw new Error(`Expected one literal ${name}`)
    return matches[0][1]
  }
  return {
    applicationId: field("applicationId", '"([^"\\r\\n]+)"'),
    versionCode: field("versionCode", "([1-9][0-9]*)"),
    versionName: field("versionName", '"([^"\\r\\n]+)"'),
  }
}

type Version = ReturnType<typeof readVersion>

export function validateTag(tag: string, version: Version) {
  if (!tag.startsWith("v")) throw new Error("Release tag must start with v")
  parse(tag.slice(1))
  if (tag !== `v${version.versionName}`) {
    throw new Error("Release tag does not match Android versionName")
  }
}

export async function readNotes(root: string, code: string) {
  const metadata = join(root, "fastlane/metadata/android")
  let notes = ""
  for await (const locale of Deno.readDir(metadata)) {
    if (!locale.isDirectory) continue
    const path = join(metadata, locale.name, "changelogs", `${code}.txt`)
    const text = (await Deno.readTextFile(path)).trim()
    if (!text) throw new Error(`Empty changelog: ${path}`)
    if (locale.name === "en-US") notes = text + "\n"
  }
  if (!notes) throw new Error("Missing en-US changelog")
  return notes
}

export const command = (program: string, args: string[]) => {
  const executable = program === "git"
    ? Deno.env.get("GIT_PATH")
    : program === "gh"
    ? Deno.env.get("GH_PATH")
    : program
  if (!executable) throw new Error(`Missing executable path for ${program}`)
  return $`${[executable, ...args]}`.clearEnv().env({
    PATH: Deno.env.get("PATH"),
    HOME: Deno.env.get("HOME"),
    XDG_CONFIG_HOME: Deno.env.get("XDG_CONFIG_HOME"),
    GH_TOKEN: program === "gh" ? Deno.env.get("GH_TOKEN") : undefined,
  }).quiet()
}

type Runner = typeof command

export async function verifyTag(
  tag: string,
  version: Version,
  run: Runner = command,
) {
  validateTag(tag, version)
  await run("git", ["show-ref", "--verify", `refs/tags/${tag}`])
  const commit = await run("git", ["rev-parse", `refs/tags/${tag}^{commit}`])
    .text()
  const head = await run("git", ["rev-parse", "HEAD"]).text()
  if (commit !== head) {
    throw new Error("Release tag does not point to the checked-out commit")
  }
}

export function verifyApk(badging: string, version: Version) {
  const packageLine =
    badging.split("\n").find((line) => line.startsWith("package: ")) ?? ""
  for (
    const [field, value] of Object.entries({
      name: version.applicationId,
      versionCode: version.versionCode,
      versionName: version.versionName,
    })
  ) {
    if (
      packageLine.match(new RegExp(`(?:^| )${field}='([^']*)'`))?.[1] !== value
    ) {
      throw new Error(`APK ${field} does not match Gradle metadata`)
    }
  }
}

export async function publishRelease(
  options: { tag: string; apk: string; notes: string; repository: string },
  run: Runner = command,
) {
  const { tag, apk, notes, repository } = options
  const release = await run("gh", [
    "api",
    `repos/${repository}/releases/tags/${tag}`,
  ]).noThrow()
  if (release.code !== 0) {
    if (!release.stderr.includes("(HTTP 404)")) {
      throw new Error("Unable to look up GitHub release")
    }
    await run("gh", [
      "release",
      "create",
      tag,
      apk,
      "--verify-tag",
      "--title",
      tag,
      "--notes-file",
      notes,
    ])
    return
  }
  const { body } = v.parse(
    v.object({ body: v.nullable(v.string()) }),
    release.stdoutJson,
  )
  await run("gh", ["release", "upload", tag, apk, "--clobber"])
  if (!body?.trim() || body === `Release ${tag}`) {
    await run("gh", ["release", "edit", tag, "--notes-file", notes])
  }
}

if (import.meta.main) {
  const stage = v.parse(
    v.picklist(["prepare", "verify", "publish"]),
    Deno.args[0],
  )
  const version = readVersion(await Deno.readTextFile("app/build.gradle.kts"))
  const tag = Deno.env.get("RELEASE_TAG") ?? ""
  const notes = await readNotes(
    fromFileUrl(new URL("../", import.meta.url)),
    version.versionCode,
  )
  if (tag) await verifyTag(tag, version)
  if (stage === "prepare") {
    await Deno.mkdir("build", { recursive: true })
    await Deno.writeTextFile("build/release-notes.txt", notes)
    const output = Deno.env.get("GITHUB_OUTPUT")
    if (output) {
      await Deno.writeTextFile(
        output,
        `tag=${tag}\nversion=${version.versionName}\napk=app/build/outputs/apk/release/gc-${version.versionName}.apk\n`,
        { append: true },
      )
    }
  } else {
    if (!tag) throw new Error("Release tag is required")
    const sdk = Deno.env.get("ANDROID_HOME")
    if (!sdk) throw new Error("ANDROID_HOME is required")
    const apk = stage === "publish"
      ? `app/build/outputs/apk/release/gc-${version.versionName}.apk`
      : "app/build/outputs/apk/release/app-release-unsigned.apk"
    const badging = await command(join(sdk, "build-tools/36.0.0/aapt"), [
      "dump",
      "badging",
      apk,
    ]).text()
    verifyApk(badging, version)
    if (stage === "publish") {
      const repository = Deno.env.get("GITHUB_REPOSITORY")
      if (!repository) throw new Error("GITHUB_REPOSITORY is required")
      await publishRelease({
        tag,
        apk,
        notes: "build/release-notes.txt",
        repository,
      })
    }
  }
}
