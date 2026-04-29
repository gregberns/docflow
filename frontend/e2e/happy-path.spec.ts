import { expect, test } from "@playwright/test";
import {
  approveFromApproval,
  approveFromReview,
  expectFormBranch,
  expectStage,
  expectStatus,
  gotoPinnacleDashboard,
  openFirstDocument,
  uploadPinnacleInvoice,
} from "./helpers";

test.describe("happy path: Pinnacle Invoice", () => {
  test("upload → Review → Attorney Approval → Billing Approval → Filed", async ({ page }) => {
    await gotoPinnacleDashboard(page);
    await uploadPinnacleInvoice(page);

    const documentId = await openFirstDocument(page);
    expect(documentId).toMatch(/[0-9a-f-]{36}/i);

    await expectFormBranch(page, "REVIEW");
    await expectStatus(page, "AWAITING_REVIEW");

    await approveFromReview(page);

    await expectFormBranch(page, "APPROVAL");
    await expectStage(page, "Attorney Approval");
    await expectStatus(page, "AWAITING_APPROVAL");

    await approveFromApproval(page);

    await expectFormBranch(page, "APPROVAL");
    await expectStage(page, "Billing Approval");
    await expectStatus(page, "AWAITING_APPROVAL");

    await approveFromApproval(page);

    await expectFormBranch(page, "TERMINAL");
    await expectStatus(page, "FILED");
  });
});
