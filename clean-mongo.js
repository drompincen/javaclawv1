db = db.getSiblingDB("javaclaw");

// Old domain collections removed in v2 migration — drop them entirely
var oldCollections = [
  "tickets", "objectives", "blindspots", "delta_packs",
  "resources", "resource_assignments", "checklists", "phases",
  "milestones", "reminders", "ideas", "uploads", "links",
  "intakes", "reconciliations", "checklist_templates"
];
oldCollections.forEach(function(c) {
  if (db.getCollectionNames().indexOf(c) >= 0) {
    db[c].drop();
    print("  DROPPED: " + c);
  }
});

// Clean remaining collections (except agents)
var cols = db.getCollectionNames();
print("Collections after drop: " + cols.length);
cols.forEach(function(c) {
  if (c === "agents") {
    print("  KEEP: " + c + " (" + db[c].countDocuments() + " docs)");
  } else {
    var count = db[c].countDocuments();
    db[c].deleteMany({});
    print("  CLEANED: " + c + " (removed " + count + " docs)");
  }
});
print("Done. Agents preserved, old domain collections dropped.");
