import { expect, test } from "@playwright/test";
import {
  approveFromApproval,
  approveFromReview,
  expectFormBranch,
  expectStage,
  expectStatus,
  flagFromApproval,
  gotoPinnacleDashboard,
  openFirstDocument,
  resolveFromFlagged,
  uploadPinnacleInvoice,
} from "./helpers";

const ATTORNEY_STAGE_ID = "Attorney Approval";

test.describe("flag and resolve: origin restoration", () => {
  test("flag from Attorney → resolve → return to Attorney → approve to Filed", async ({ page }) => {
    await gotoPinnacleDashboard(page);
    await uploadPinnacleInvoice(page);

    await openFirstDocument(page);

    await expectFormBranch(page, "REVIEW");
    await expectStatus(page, "AWAITING_REVIEW");

    await approveFromReview(page);

    await expectFormBranch(page, "APPROVAL");
    await expectStage(page, ATTORNEY_STAGE_ID);
    await expectStatus(page, "AWAITING_APPROVAL");

    await flagFromApproval(page, "Vendor name needs verification");

    await expectFormBranch(page, "REVIEW_FLAGGED");
    await expect(page.getByTestId("flag-banner")).toBeVisible();
    await expect(page.getByTestId("flag-banner-origin")).toHaveText(ATTORNEY_STAGE_ID);
    await expect(page.getByTestId("flag-banner-comment")).toContainText(
      "Vendor name needs verification",
    );
    await expectStatus(page, "FLAGGED");

    await resolveFromFlagged(page);

    await expectFormBranch(page, "APPROVAL");
    await expectStage(page, ATTORNEY_STAGE_ID);
    await expectStatus(page, "AWAITING_APPROVAL");
    await expect(page.getByTestId("flag-banner")).toHaveCount(0);

    await approveFromApproval(page);

    await expectFormBranch(page, "APPROVAL");
    await expectStage(page, "Billing Approval");
    await expectStatus(page, "AWAITING_APPROVAL");

    await approveFromApproval(page);

    await expectFormBranch(page, "TERMINAL");
    await expectStatus(page, "FILED");
  });
});
