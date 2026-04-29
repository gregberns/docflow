import { useEffect, useMemo } from "react";
import {
  FormProvider,
  useForm,
  useFieldArray,
  useFormContext,
  type Resolver,
} from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import type { DocumentView, WorkflowStatus } from "../types/readModels";
import type { FieldSchema } from "../types/schema";
import type { StageSummary } from "../types/workflow";
import { buildZodFromFieldSchema } from "../schemas/buildZodFromFieldSchema";
import { ApprovalSummary } from "./ApprovalSummary";
import { TerminalSummary } from "./TerminalSummary";
import { FlagBanner } from "./FlagBanner";
import { ReextractionInProgressBanner } from "./ReextractionInProgressBanner";
import { ReextractionFailedBanner } from "./ReextractionFailedBanner";

type FormValues = Record<string, unknown>;

export interface FormPanelHandlers {
  onApprove?: () => void;
  onReject?: () => void;
  onFlag?: () => void;
  onResolve?: () => void;
  onBackToDocuments?: () => void;
  onDocumentTypeChange?: (newType: string) => void;
  onSubmitFields?: (values: FormValues) => void;
}

interface FormPanelProps {
  document: DocumentView;
  fields: FieldSchema[];
  docTypeOptions: ReadonlyArray<string>;
  stage?: StageSummary | null;
  pendingNewDocumentType?: string | null;
  reextractionFailureMessage?: string | null;
  isSubmitting?: boolean;
  handlers?: FormPanelHandlers;
}

function noop() {
  /* placeholder until df-6m8.9 wires real action handlers */
}

const TERMINAL_STATUSES: ReadonlyArray<WorkflowStatus> = ["FILED", "REJECTED"];

type Branch =
  | "REEXTRACTION_IN_PROGRESS"
  | "REEXTRACTION_FAILED"
  | "REVIEW"
  | "REVIEW_FLAGGED"
  | "APPROVAL"
  | "TERMINAL";

function pickBranch(
  reextractionStatus: DocumentView["reextractionStatus"],
  currentStatus: WorkflowStatus | null,
  workflowOriginStage: string | null,
): Branch {
  if (reextractionStatus === "IN_PROGRESS") return "REEXTRACTION_IN_PROGRESS";
  if (reextractionStatus === "FAILED") return "REEXTRACTION_FAILED";
  if (currentStatus && TERMINAL_STATUSES.includes(currentStatus)) return "TERMINAL";
  if (currentStatus === "AWAITING_APPROVAL") return "APPROVAL";
  if (currentStatus === "FLAGGED" || workflowOriginStage) return "REVIEW_FLAGGED";
  return "REVIEW";
}

export function FormPanel({
  document,
  fields,
  docTypeOptions,
  stage,
  pendingNewDocumentType,
  reextractionFailureMessage,
  isSubmitting,
  handlers,
}: FormPanelProps) {
  const { reextractionStatus, currentStatus, workflowOriginStage } = document;

  const branch = pickBranch(reextractionStatus, currentStatus, workflowOriginStage);

  return (
    <div data-testid="form-panel" data-branch={branch}>
      {branch === "REEXTRACTION_IN_PROGRESS" && (
        <>
          <ReextractionInProgressBanner newDocumentType={pendingNewDocumentType} />
          <ReviewBranch
            document={document}
            fields={fields}
            docTypeOptions={docTypeOptions}
            flagged={Boolean(workflowOriginStage)}
            isSubmitting={Boolean(isSubmitting)}
            disabled={true}
            handlers={handlers}
            hideActions={true}
          />
        </>
      )}
      {branch === "REEXTRACTION_FAILED" && (
        <>
          <ReextractionFailedBanner message={reextractionFailureMessage} />
          <ReviewBranch
            document={document}
            fields={fields}
            docTypeOptions={docTypeOptions}
            flagged={Boolean(workflowOriginStage)}
            isSubmitting={Boolean(isSubmitting)}
            disabled={false}
            handlers={handlers}
          />
        </>
      )}
      {branch === "REVIEW" && (
        <ReviewBranch
          document={document}
          fields={fields}
          docTypeOptions={docTypeOptions}
          flagged={false}
          isSubmitting={Boolean(isSubmitting)}
          disabled={false}
          handlers={handlers}
        />
      )}
      {branch === "REVIEW_FLAGGED" && (
        <>
          <FlagBanner originStage={workflowOriginStage ?? ""} comment={document.flagComment} />
          <ReviewBranch
            document={document}
            fields={fields}
            docTypeOptions={docTypeOptions}
            flagged={true}
            isSubmitting={Boolean(isSubmitting)}
            disabled={false}
            handlers={handlers}
          />
        </>
      )}
      {branch === "APPROVAL" && (
        <ApprovalSummary
          fields={fields}
          values={document.extractedFields}
          stageDisplayName={document.currentStageDisplayName}
          role={stage?.role ?? null}
          onApprove={handlers?.onApprove ?? noop}
          onFlag={handlers?.onFlag ?? noop}
          isSubmitting={isSubmitting ?? false}
        />
      )}
      {branch === "TERMINAL" && currentStatus && (
        <TerminalSummary
          fields={fields}
          values={document.extractedFields}
          status={currentStatus}
          stageDisplayName={document.currentStageDisplayName}
          onBackToDocuments={handlers?.onBackToDocuments ?? noop}
        />
      )}
    </div>
  );
}

interface ReviewBranchProps {
  document: DocumentView;
  fields: FieldSchema[];
  docTypeOptions: ReadonlyArray<string>;
  flagged: boolean;
  isSubmitting: boolean;
  disabled: boolean;
  handlers?: FormPanelHandlers | undefined;
  hideActions?: boolean | undefined;
}

function buildDefaultValues(
  fields: FieldSchema[],
  values: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const field of fields) {
    const upper = field.type.toUpperCase();
    const raw = values[field.name];
    if (upper === "ARRAY") {
      out[field.name] = Array.isArray(raw) ? raw : [];
    } else {
      out[field.name] = raw ?? "";
    }
  }
  return out;
}

function ReviewBranch({
  document,
  fields,
  docTypeOptions,
  flagged,
  isSubmitting,
  disabled,
  handlers,
  hideActions,
}: ReviewBranchProps) {
  const schema = useMemo(() => buildZodFromFieldSchema(fields), [fields]);
  const defaultValues = useMemo(
    () => buildDefaultValues(fields, document.extractedFields),
    [fields, document.extractedFields],
  );

  const methods = useForm<FormValues>({
    defaultValues,
    resolver: zodResolver(schema) as unknown as Resolver<FormValues>,
    mode: "onSubmit",
  });

  useEffect(() => {
    methods.reset(defaultValues);
  }, [document.documentId, document.reextractionStatus, defaultValues, methods]);

  const {
    handleSubmit,
    formState: { isSubmitting: rhfSubmitting },
  } = methods;

  const submitting = isSubmitting || rhfSubmitting;

  const onSubmit = (formValues: FormValues) => {
    handlers?.onSubmitFields?.(formValues);
    if (flagged) {
      handlers?.onResolve?.();
    } else {
      handlers?.onApprove?.();
    }
  };

  return (
    <FormProvider {...methods}>
      <form
        data-testid="review-form"
        data-flagged={flagged ? "true" : "false"}
        onSubmit={handleSubmit(onSubmit)}
      >
        <label data-testid="doctype-field">
          <span>Document Type</span>
          <select
            data-testid="doctype-select"
            value={document.detectedDocumentType ?? ""}
            disabled={disabled}
            onChange={(event) => handlers?.onDocumentTypeChange?.(event.target.value)}
          >
            {docTypeOptions.map((opt) => (
              <option key={opt} value={opt}>
                {opt}
              </option>
            ))}
          </select>
        </label>
        <fieldset disabled={disabled} data-testid="review-fields">
          {fields.map((field) => (
            <FieldRow key={field.name} field={field} />
          ))}
        </fieldset>
        {!hideActions && (
          <div data-testid="review-action-bar">
            {flagged ? (
              <button type="submit" data-testid="resolve-button" disabled={disabled || submitting}>
                Resolve
              </button>
            ) : (
              <button type="submit" data-testid="approve-button" disabled={disabled || submitting}>
                Approve
              </button>
            )}
            <button
              type="button"
              data-testid="reject-button"
              onClick={handlers?.onReject ?? noop}
              disabled={disabled || submitting}
            >
              Reject
            </button>
          </div>
        )}
      </form>
    </FormProvider>
  );
}

function FieldRow({ field }: { field: FieldSchema }) {
  const upper = field.type.toUpperCase();
  const {
    register,
    formState: { errors },
  } = useFormContext<FormValues>();
  const errorEntry = errors[field.name] as { message?: string } | undefined;
  const errorMsg = errorEntry?.message;

  if (upper === "ARRAY") {
    return <ArrayFieldRow field={field} />;
  }

  if (upper === "ENUM") {
    const values = field.enumValues ?? [];
    return (
      <label data-testid={`field-${field.name}`}>
        <span>{field.name}</span>
        <select
          data-testid={`input-${field.name}`}
          {...register(field.name)}
          aria-required={field.required}
        >
          <option value="">— select —</option>
          {values.map((v) => (
            <option key={v} value={v}>
              {v}
            </option>
          ))}
        </select>
        {errorMsg && (
          <span role="alert" data-testid={`error-${field.name}`}>
            {errorMsg}
          </span>
        )}
      </label>
    );
  }

  const inputType = upper === "DATE" ? "date" : "text";
  return (
    <label data-testid={`field-${field.name}`}>
      <span>{field.name}</span>
      <input
        type={inputType}
        data-testid={`input-${field.name}`}
        {...register(field.name)}
        aria-required={field.required}
        inputMode={upper === "DECIMAL" ? "decimal" : undefined}
      />
      {errorMsg && (
        <span role="alert" data-testid={`error-${field.name}`}>
          {errorMsg}
        </span>
      )}
    </label>
  );
}

function ArrayFieldRow({ field }: { field: FieldSchema }) {
  const itemFields = field.itemFields ?? [];
  const { register, control } = useFormContext<FormValues>();
  // RHF's useFieldArray expects a typed path against the form shape; FormValues is a
  // generic record so we cast control to satisfy the type without surrendering runtime safety.
  const {
    fields: rows,
    append,
    remove,
  } = useFieldArray({
    control: control as never,
    name: field.name as never,
  });

  return (
    <div data-testid={`field-${field.name}`}>
      <span>{field.name}</span>
      <table data-testid={`field-array-${field.name}`}>
        <thead>
          <tr>
            {itemFields.map((child) => (
              <th key={child.name}>{child.name}</th>
            ))}
            <th aria-label="Row controls" />
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={row.id} data-testid={`field-array-${field.name}-row`}>
              {itemFields.map((child) => (
                <td key={child.name}>
                  <input
                    data-testid={`input-${field.name}-${index}-${child.name}`}
                    {...register(`${field.name}.${index}.${child.name}` as never)}
                  />
                </td>
              ))}
              <td>
                <button
                  type="button"
                  data-testid={`remove-row-${field.name}-${index}`}
                  onClick={() => remove(index)}
                >
                  Remove
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button
        type="button"
        data-testid={`add-row-${field.name}`}
        onClick={() => {
          const blank: Record<string, unknown> = {};
          for (const child of itemFields) {
            blank[child.name] = "";
          }
          append(blank);
        }}
      >
        Add row
      </button>
    </div>
  );
}
