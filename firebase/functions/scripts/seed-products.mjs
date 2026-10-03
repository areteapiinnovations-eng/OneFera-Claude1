// Seeds /products from the app's sample catalogue (app/src/main/assets/catalog/products.json),
// so a fresh Firebase project has the same shop as demo mode.
//
//   cd firebase/functions
//   GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json npm run seed -- --project <project-id>
//   # or against the emulator:  FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 npm run seed -- --project demo-onefera
//
// Re-running updates titles, prices and images but keeps live stock and sales counters.
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";

const here = dirname(fileURLToPath(import.meta.url));
const catalogPath = resolve(here, "../../../app/src/main/assets/catalog/products.json");
const projectFlag = process.argv.indexOf("--project");
const projectId = projectFlag > 0 ? process.argv[projectFlag + 1] : process.env.GCLOUD_PROJECT;
if (!projectId) {
  console.error("Pass --project <firebase-project-id>");
  process.exit(1);
}

initializeApp({ projectId });
const db = getFirestore();

/** Same prefixes as Product.keywordsFor() in the app, used for product search. */
function keywords(text) {
  const words = text.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((w) => w.length >= 2);
  const out = new Set();
  for (const w of words) for (let n = 2; n <= Math.min(w.length, 15); n++) out.add(w.slice(0, n));
  return [...out].slice(0, 200);
}

const categoryLabels = {
  Mobiles: "Mobiles", Electronics: "Electronics", Fashion: "Fashion", Beauty: "Beauty",
  Appliances: "Appliances", Groceries: "Groceries", KidsToys: "Kids & Toys", More: "More",
};

const products = JSON.parse(readFileSync(catalogPath, "utf8"));
let written = 0;
for (let i = 0; i < products.length; i += 400) {
  const batch = db.batch();
  for (const p of products.slice(i, i + 400)) {
    const ref = db.collection("products").doc(p.id);
    const existing = await ref.get();
    let seller = { uid: p.sellerId ?? "", displayName: p.brand, username: "", avatarUrl: null, verified: false, isPrivate: false };
    if (p.sellerId) {
      const user = await db.collection("users").doc(p.sellerId).get();
      if (user.exists) {
        const u = user.data();
        seller = { uid: p.sellerId, displayName: u.displayName ?? "", username: u.username ?? "", avatarUrl: u.avatarUrl ?? null, verified: !!u.verified, isPrivate: !!u.isPrivate };
      }
    }
    const data = {
      title: p.title,
      brand: p.brand,
      description: p.description,
      category: p.category,
      price: p.price,
      mrp: p.mrp,
      images: p.images,
      highlights: p.highlights ?? [],
      variants: p.variants ?? [],
      rating: p.rating,
      ratingCount: p.ratingCount,
      sellerId: p.sellerId ?? "",
      seller,
      isDrop: !!p.isDrop,
      keywords: keywords(`${p.title} ${p.brand} ${categoryLabels[p.category] ?? ""}`),
    };
    if (!existing.exists) Object.assign(data, { stock: p.stock, soldCount: p.soldCount, createdAt: FieldValue.serverTimestamp() });
    batch.set(ref, data, { merge: true });
    written++;
  }
  await batch.commit();
}
console.log(`Seeded ${written} products into ${projectId}.`);
