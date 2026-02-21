db = db.getSiblingDB("javaclaw");
var cols = db.getCollectionNames();
print("Collections before cleanup: " + cols.length);
cols.forEach(function(c) {
  if (c === "agents") {
    print("  KEEP: " + c + " (" + db[c].countDocuments() + " docs)");
  } else {
    var count = db[c].countDocuments();
    db[c].deleteMany({});
    print("  CLEANED: " + c + " (removed " + count + " docs)");
  }
});
print("Done. Agents preserved.");
