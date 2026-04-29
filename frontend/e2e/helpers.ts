import path from "node:path";
import { fileURLToPath } from "node:url";
import type { Locator, Page } from "@playwright/test";
import { expect } from "@playwright/test";

const here = path.dirname(fileURLToPath(import.meta.url));

export const PINNACLE_ORG_ID = "pinnacle-legal";
export const SAMPLE_INVOICE_PATH = path.resolve(
  here,
  "../../problem-statement/samples/pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.pdf",
);

export const PROCESSING_TIMEOUT_MS = 180_000;
export const TRANSITION_TIMEOUT_MS = 30_000;

export async function gotoPinnacleDashboard(page: Page): Promise<void> {
  await page.goto(`/org/${PINNACLE_ORG_ID}/dashboard`);
  await expect(page.getByTestId("dashboard-page")).toBeVisible();
}

export async function uploadPinnacleInvoice(page: Page): Promise<void> {
  const input = page.getByTestId("upload-file-input");
  await input.setInputFiles(SAMPLE_INVOICE_PATH);
}

export async function waitForDocumentRow(page: Page): Promise<Locator> {
  const row = page.getByTestId("document-row").first();
  await expect(row).toBeVisible({ timeout: PROCESSING_TIMEOUT_MS });
  return row;
}

export async function openFirstDocument(page: Page): Promise<string> {
  const row = await waitForDocumentRow(page);
  const documentId = await row.getAttribute("data-document-id");
  if (!documentId) {
    throw new Error("document-row missing data-document-id");
  }
  await row.click();
  await expect(page.getByTestId("document-detail-page")).toBeVisible();
  return documentId;
}

export async function expectFormBranch(
  page: Page,
  branch: "REVIEW" | "REVIEW_FLAGGED" | "APPROVAL" | "TERMINAL",
): Promise<void> {
  await expect(page.getByTestId("form-panel")).toHaveAttribute("data-branch", branch, {
    timeout: TRANSITION_TIMEOUT_MS,
  });
}

export async function expectStatus(page: Page, status: string): Promise<void> {
  await expect(page.getByTestId("document-status")).toHaveText(status, {
    timeout: TRANSITION_TIMEOUT_MS,
  });
}

export async function expectStage(page: Page, stageDisplayName: string): Promise<void> {
  await expect(page.getByTestId("document-stage")).toHaveText(stageDisplayName, {
    timeout: TRANSITION_TIMEOUT_MS,
  });
}

export async function approveFromReview(page: Page): Promise<void> {
  await expectFormBranch(page, "REVIEW");
  await page.getByTestId("approve-button").click();
}

export async function approveFromApproval(page: Page): Promise<void> {
  await expectFormBranch(page, "APPROVAL");
  await page.getByTestId("approve-button").click();
}

export async function flagFromApproval(page: Page, comment: string): Promise<void> {
  await expectFormBranch(page, "APPROVAL");
  await page.getByTestId("flag-button").click();
  const modal = page.getByTestId("flag-modal");
  await expect(modal).toBeVisible();
  await modal.getByTestId("flag-modal-comment").fill(comment);
  await modal.getByTestId("flag-modal-submit").click();
  await expect(modal).toBeHidden({ timeout: TRANSITION_TIMEOUT_MS });
}

export async function resolveFromFlagged(page: Page): Promise<void> {
  await expectFormBranch(page, "REVIEW_FLAGGED");
  await page.getByTestId("resolve-button").click();
}
