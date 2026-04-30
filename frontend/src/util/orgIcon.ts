const ICON_GLYPHS: Record<string, string> = {
  "icon-bistro": "\u{1F37D}",
  "icon-legal": "⚖",
  "icon-construction": "\u{1F6A7}",
  bistro: "\u{1F37D}",
  legal: "⚖",
  construction: "\u{1F6A7}",
  hardhat: "\u{1F477}",
  "knife-fork": "\u{1F37D}",
  briefcase: "\u{1F4BC}",
  hammer: "\u{1F528}",
  toolbox: "\u{1F9F0}",
  scales: "⚖",
  building: "\u{1F3E2}",
  shop: "\u{1F3EA}",
};

const FALLBACK_GLYPH = "\u{1F4C1}";

export function resolveOrgIcon(name: string | undefined): string {
  if (!name) return FALLBACK_GLYPH;
  return ICON_GLYPHS[name] ?? FALLBACK_GLYPH;
}
