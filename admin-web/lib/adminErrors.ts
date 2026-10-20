// SPDX-License-Identifier: GPL-3.0-or-later
/** Maps a Firebase error code to a dictionary key the UI can show. */
export function adminErrorKey(code: string | undefined): string {
  switch (code) {
    case "functions/not-found":
    case "functions/unavailable":
    case "functions/internal":
      return "err.functions";
    case "functions/permission-denied":
    case "permission-denied":
    case "firestore/permission-denied":
      return "err.permission";
    case "functions/unauthenticated":
      return "err.signin";
    case "functions/invalid-argument":
      return "err.invalid";
    case "auth/invalid-credential":
    case "auth/wrong-password":
    case "auth/user-not-found":
    case "auth/invalid-email":
      return "err.login";
    case "auth/too-many-requests":
      return "err.tooMany";
    case "auth/popup-closed-by-user":
    case "auth/cancelled-popup-request":
      return "err.cancelled";
    case "auth/network-request-failed":
    case "unavailable":
      return "err.network";
    default:
      return "err.unknown";
  }
}
