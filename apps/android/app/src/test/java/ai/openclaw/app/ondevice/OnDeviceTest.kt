package ai.openclaw.app.ondevice

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnDeviceTest {
  @get:Rule val tmp = TemporaryFolder()

  private val layout get() = OnDeviceLayout(File("/data/orion"), File("/data/app/lib/arm64"))
  private val secrets = OnDeviceSecrets("vk", "wh", "tok")

  @Test
  fun environmentCarriesSecretsPathsAndOnlyWhitelistedCredentials() {
    val env =
      OnDevicePlan.environment(
        layout,
        secrets,
        OnDeviceSettings(ownerEmail = " me@x.com ", ownerWhatsapp = "+971500000000", credentials = mapOf("RESEND_API_KEY" to "re_1", "LD_PRELOAD" to "/evil.so", "PATH" to "/evil")),
      )
    assertEquals("tok", env["OPENCLAW_GATEWAY_TOKEN"])
    assertEquals("vk", env["ORION_VAULT_KEY"])
    assertEquals("me@x.com", env["PERSONAL_OWNER_EMAILS"])
    assertEquals("re_1", env["RESEND_API_KEY"])
    assertEquals("/data/orion/bin:/system/bin", env["PATH"])
    assertFalse(env.containsKey("LD_PRELOAD"))
    assertEquals("/data/orion/runtime/lib", env["LD_LIBRARY_PATH"])
    assertEquals("/data/orion/libexec/git-core", env["GIT_EXEC_PATH"])
  }

  @Test
  fun configBindsLoopbackByDefaultAndLanOnRequest() {
    val loop = JSONObject(OnDevicePlan.configJson(layout, OnDeviceSettings(ownerWhatsapp = "+971500000000")))
    assertEquals("loopback", loop.getJSONObject("gateway").getString("bind"))
    assertEquals("token", loop.getJSONObject("gateway").getJSONObject("auth").getString("mode"))
    assertEquals("whatsapp:+971500000000", loop.getJSONObject("commands").getJSONArray("ownerAllowFrom").getString(0))
    val lan = JSONObject(OnDevicePlan.configJson(layout, OnDeviceSettings(lan = true)))
    assertEquals("lan", lan.getJSONObject("gateway").getString("bind"))
  }

  @Test
  fun generatedSecretsAreUniqueAndSized() {
    val a = OnDeviceSecrets.generate()
    val b = OnDeviceSecrets.generate()
    assertEquals(32, java.util.Base64.getDecoder().decode(a.vaultKey).size)
    assertEquals(48, a.gatewayToken.length)
    assertTrue(a.gatewayToken != b.gatewayToken)
  }

  private fun tarEntry(
    name: String,
    body: ByteArray,
    type: Char = '0',
    mode: Int = 420,
    link: String = "",
  ): ByteArray {
    val header = ByteArray(512)
    fun put(
      off: Int,
      text: String,
    ) = text.toByteArray().copyInto(header, off)
    put(0, name)
    put(100, "%07o".format(mode))
    put(124, "%011o".format(body.size))
    header[156] = type.code.toByte()
    put(157, link)
    val sum = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 0xff }
    put(148, "%06o".format(sum))
    header[154] = 0
    header[155] = ' '.code.toByte()
    return header + body + ByteArray((512 - body.size % 512) % 512)
  }

  private fun gz(vararg parts: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { g ->
      parts.forEach(g::write)
      g.write(ByteArray(1024))
    }
    return out.toByteArray()
  }

  @Test
  fun tarExtractsFilesDirsSymlinksAndKeepsExecutableBit() {
    val dest = tmp.newFolder("dest")
    val archive = gz(tarEntry("bin/", ByteArray(0), '5'), tarEntry("bin/run", "echo".toByteArray(), mode = 493), tarEntry("bin/alias", ByteArray(0), '2', link = "run"))
    TarGz.extract(archive.inputStream(), dest)
    assertEquals("echo", File(dest, "bin/run").readText())
    assertTrue(File(dest, "bin/run").canExecute())
    assertEquals("echo", File(dest, "bin/alias").readText())
  }

  @Test
  fun tarRejectsPathTraversal() {
    val dest = tmp.newFolder("safe")
    try {
      TarGz.extract(gz(tarEntry("../escape", "x".toByteArray())).inputStream(), dest)
      fail("expected traversal to be rejected")
    } catch (_: java.io.IOException) {
      assertFalse(File(dest.parentFile, "escape").exists())
    }
  }
}
