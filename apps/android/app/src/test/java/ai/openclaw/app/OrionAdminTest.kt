package ai.openclaw.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionAdminTest {
  private fun parse(json: String) = parseOrionOverview(Json.parseToJsonElement(json).jsonObject)

  @Test
  fun unconfiguredGatewayReportsTheReason() {
    val summary = parse("""{"configured":false,"reason":"Set ORION_STATE_DIR"}""")
    assertEquals(OrionAdminSummary.Unconfigured("Set ORION_STATE_DIR"), summary)
  }

  @Test
  fun malformedResponsesAreRejectedInsteadOfShownAsEmpty() {
    assertNull(parse("""{"configured":true}"""))
    assertNull(parseOrionOverview(null))
  }

  @Test
  fun parsesAnAdminOverview() {
    val summary =
      parse(
        """
        {"configured":true,"needsBootstrap":false,"vaultAvailable":true,
         "me":{"profileId":"vaishnav","role":"owner","permissions":["members.manage","tasks.steer"]},
         "members":[{"profileId":"alice","role":"engineer","workspaces":["hewar"],"githubLogin":"alice-gh","addresses":["a@x.dev"]}],
         "connectors":[
           {"kind":"whatsapp","label":"WhatsApp","scopes":["org"],"fields":["account"],
            "policy":{"userScope":false,"orgFallback":true},"org":{"connected":true,"by":"vaishnav"},"mine":{"connected":false}},
           {"kind":"google","label":"Google","scopes":["org","user"],"fields":["clientId"],
            "policy":{"userScope":true,"orgFallback":false},"org":{"connected":false},"mine":{"connected":true},
            "people":[{"userId":"alice","updatedAt":"2026-03-01T00:00:00Z"}]}],
         "tasks":[{"id":"t1","workspace":"hewar","status":"NEEDS_APPROVAL","source":"Chat","title":"Upload broken",
                   "sharedWith":["bob"],"updatedAt":"2026-03-01T00:00:00Z","summary":"s","canShare":true}],
         "workspaces":[{"id":"hewar","name":"Hewar","kind":"project","grants":["git.write"],"requireApproval":["prod.restart"],"production":true,"members":["alice"]}],
         "health":{"agentBound":true,"githubToken":false,"webhookSecret":true,"vaultKey":true},
         "audit":[{"at":"2026-03-01T00:00:00Z","actor":"vaishnav","action":"connect","connector":"google","scope":"user:alice"}]}
        """.trimIndent(),
      )
    val ready = summary as OrionAdminSummary.Ready
    assertTrue(ready.isAdmin)
    assertTrue(ready.can("members.manage"))
    assertFalse(ready.can("connectors.audit"))
    assertEquals(listOf("alice"), ready.members.map { it.profileId })
    val whatsapp = ready.connectors.first { it.kind == "whatsapp" }
    assertTrue(whatsapp.orgOnly)
    assertFalse(whatsapp.userScopeAllowed)
    assertEquals("vaishnav", whatsapp.orgBy)
    val google = ready.connectors.first { it.kind == "google" }
    assertFalse(google.orgOnly)
    assertTrue(google.mineConnected)
    assertEquals(listOf("alice"), google.people?.map { it.userId })
    assertTrue(ready.tasks.single().needsPerson)
    assertFalse(ready.tasks.single().finished)
    assertTrue(ready.workspaces.single().production)
    assertFalse(ready.health.githubToken)
    assertEquals(1, ready.audit.size)
  }

  @Test
  fun nonAdminsGetNoPeopleListOrAuditAndNoPeopleOnConnectors() {
    val ready =
      parse(
        """{"configured":true,"me":{"profileId":"bob","role":"member","permissions":["tools.personal"]},
            "connectors":[{"kind":"github","label":"GitHub","scopes":["org","user"],"fields":["token"],
            "policy":{"userScope":true,"orgFallback":true},"org":{"connected":true},"mine":{"connected":false}}]}""",
      ) as OrionAdminSummary.Ready
    assertFalse(ready.isAdmin)
    assertTrue(ready.audit.isEmpty())
    assertNull(ready.connectors.single().people)
    assertNull(ready.connectors.single().orgBy)
  }

  @Test
  fun decodesGatewayQrDataUrls() {
    val png = byteArrayOf(1, 2, 3)
    val url = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)
    assertEquals(png.toList(), decodePngDataUrl(url)?.toList())
    assertNull(decodePngDataUrl("data:image/jpeg;base64,AAAA"))
    assertNull(decodePngDataUrl("not a data url"))
    assertNotNull(decodePngDataUrl("data:image/png;base64,AAAA"))
  }

  @Test
  fun rolesHaveLabelsAndBlurbs() {
    ORION_ROLES.forEach {
      assertTrue(orionRoleLabel(it).first().isUpperCase())
      assertTrue(orionRoleBlurb(it).isNotBlank())
    }
  }
}
