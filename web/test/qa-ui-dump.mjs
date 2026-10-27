// Drive the real UI path to generate an invitation and verify the share link.
export default async function run(page, ui) {
  const snap = await ui.snapshot({ full: true });
  return snap;
}
