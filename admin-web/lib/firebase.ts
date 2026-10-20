// SPDX-License-Identifier: GPL-3.0-or-later
import { getApp, getApps, initializeApp, type FirebaseApp } from "firebase/app";
import { getAuth, type Auth } from "firebase/auth";
import { getFirestore, type Firestore } from "firebase/firestore";
import { getFunctions, type Functions } from "firebase/functions";

const config = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY ?? "",
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN || "mawja-app.firebaseapp.com",
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID || "mawja-app",
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID ?? "",
};

export const FUNCTIONS_REGION = process.env.NEXT_PUBLIC_FIREBASE_FUNCTIONS_REGION || "europe-west1";

/** False until a Firebase web app config is supplied at build time (.env.local or CI env). */
export const firebaseConfigured = Boolean(config.apiKey && config.appId);

let app: FirebaseApp | null = null;

function firebaseApp(): FirebaseApp {
  if (!firebaseConfigured) throw new Error("Firebase web config missing");
  if (!app) app = getApps().length ? getApp() : initializeApp(config);
  return app;
}

export const auth = (): Auth => getAuth(firebaseApp());
export const db = (): Firestore => getFirestore(firebaseApp());
export const functions = (): Functions => getFunctions(firebaseApp(), FUNCTIONS_REGION);
