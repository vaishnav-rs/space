package ai.openclaw.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatBlocksTest {
  @Test
  fun latexBecomesReadableUnicode() {
    assertEquals("E = mc²", latexToUnicode("E = mc^2"))
    assertEquals("x₁ + x₂ ≤ α · β", latexToUnicode("x_1 + x_2 \\leq \\alpha \\cdot \\beta"))
    assertEquals("(a+b)/c", latexToUnicode("\\frac{a+b}{c}"))
    assertEquals("√(x+1)", latexToUnicode("\\sqrt{x+1}"))
    assertEquals("∑ⁿ", latexToUnicode("\\sum^{n}"))
    assertEquals("aᵏ⁺¹", latexToUnicode("a^{k+1}"))
    assertEquals("a^(q)", latexToUnicode("a^{q}"))
  }

  @Test
  fun htmlWidgetKeepsStructureAndDropsScripts() {
    val blocks =
      htmlToBlocks(
        """<html><head><style>p{}</style></head><body><script>alert(1)</script>
          <h1>Build &amp; deploy</h1><p>All <b>green</b></p><ul><li>one</li><li>two</li></ul>
          <table><tr><th>a</th><th>b</th></tr><tr><td>1</td><td>2</td></tr></table></body></html>""",
      )
    assertEquals(HtmlBlock.Heading(1, "Build & deploy"), blocks[0])
    assertEquals(HtmlBlock.Paragraph("All green"), blocks[1])
    assertEquals(listOf(HtmlBlock.Item("one"), HtmlBlock.Item("two")), blocks.subList(2, 4))
    assertEquals(HtmlBlock.Table(listOf(listOf("a", "b"), listOf("1", "2"))), blocks[4])
    assertTrue(blocks.none { it.toString().contains("alert") })
  }

  @Test
  fun mermaidFlowchartParsesNodesEdgesAndLayers() {
    val d = parseMermaid("graph TD\n  A[Start] --> B{Ok?}\n  B -->|yes| C((Done))\n  B -- no --> A\n") as MermaidDiagram.Flow
    assertEquals(listOf("A", "B", "C"), d.nodes.map { it.id })
    assertEquals(NodeShape.Diamond, d.nodes[1].shape)
    assertEquals("yes", d.edges[1].label)
    assertEquals("no", d.edges[2].label)
    val layers = flowLayers(d)
    assertEquals(0, layers["A"])
    assertEquals(1, layers["B"])
    assertEquals(2, layers["C"]) // the B→A back edge must not create a cycle
  }

  @Test
  fun mermaidSequenceAndPieAndUnsupported() {
    val seq = parseMermaid("sequenceDiagram\n participant A as Alice\n A->>B: hi\n B-->>A: yo") as MermaidDiagram.Sequence
    assertEquals(listOf("Alice", "B"), seq.participants)
    assertEquals("Alice", seq.messages[0].from)
    assertEquals(2, seq.messages.size)
    assertTrue(seq.messages[1].dashed)
    val pie = parseMermaid("pie title Pets\n \"Dogs\" : 386\n \"Cats\" : 85") as MermaidDiagram.Pie
    assertEquals("Pets", pie.title)
    assertEquals(2, pie.slices.size)
    assertNull(parseMermaid("gantt\n title x"))
    assertNotNull(parseMermaid("flowchart LR\nA-->B"))
  }
}
