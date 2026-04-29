import { useEffect, useMemo } from "react";
import { FormProvider, useForm, useFormContext, type Resolver } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import type { DocumentView } from "../types/readModels";
import type { FieldSchema } from "../types/schema";
import { buildZodFromFieldSchema } from "../schemas/buildZodFromFieldSchema";
import { useDocumentActions } from "../hooks/useDocumentActions";
import { FieldArrayTable } from "./FieldArrayTable";

type FormValues = Record<string, unknown>;

export interface ReviewFormCallbacks {
  onSubmitFields?: (values: FormValues) => void;
  onDocumentTypeChange?: (newType: string) => void;
}

interface ReviewFormProps {
  document: DocumentView;
  fields: FieldSchema[];
  docTypeOptions: ReadonlyArray<string>;
  flagged: boolean;
  disabled: boolean;
  hideActions?: boolean;
  isSubmitting?: boolean;
  selectedDocumentType?: string | null;
  callbacks?: ReviewFormCallbacks;
}

const SECTION_TITLE = "mb-3 mt-1 text-11 font-bold uppercase tracking-[0.5px] text-neutral-500";

const FORM_INPUT =
  "h-[34px] w-full rounded-md border border-neutral-300 bg-card px-2.5 text-13 text-brand-navy outline-none focus:border-brand-blue focus:shadow-[0_0_0_2px_rgba(108,155,255,0.15)]";

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

export function ReviewForm({
  document,
  fields,
  docTypeOptions,
  flagged,
  disabled,
  hideActions,
  isSubmitting,
  selectedDocumentType,
  callbacks,
}: ReviewFormProps) {
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

  const setFieldError = (path: string, message: string): void => {
    methods.setError(path as never, { type: "server", message });
  };

  const actions = useDocumentActions({
    documentId: document.documentId,
    organizationId: document.organizationId,
    setFieldError,
  });

  useEffect(() => {
    methods.reset(defaultValues);
  }, [document.documentId, document.reextractionStatus, defaultValues, methods]);

  const {
    handleSubmit,
    formState: { isSubmitting: rhfSubmitting },
  } = methods;

  const submitting =
    isSubmitting ||
    rhfSubmitting ||
    actions.approve.isPending ||
    actions.resolve.isPending ||
    actions.reject.isPending;

  const onSubmit = (formValues: FormValues): void => {
    callbacks?.onSubmitFields?.(formValues);
    if (flagged) {
      actions.resolve.mutate();
    } else {
      actions.approve.mutate();
    }
  };

  const onReject = (): void => {
    actions.reject.mutate();
  };

  const arrayFields = fields.filter((f) => f.type.toUpperCase() === "ARRAY");
  const scalarFields = fields.filter((f) => f.type.toUpperCase() !== "ARRAY");

  return (
    <FormProvider {...methods}>
      <form
        data-testid="review-form"
        data-flagged={flagged ? "true" : "false"}
        onSubmit={handleSubmit(onSubmit)}
        className="flex min-h-0 flex-1 flex-col"
      >
        <div className="flex-1 overflow-y-auto px-6 py-5">
          <div className="mb-5 rounded-lg border border-neutral-200 bg-[#f9fafb] px-4 py-3.5">
            <label data-testid="doctype-field" className="block">
              <span className="mb-2 block text-11 font-bold uppercase tracking-[0.5px] text-neutral-500">
                Document Type
              </span>
              <select
                data-testid="doctype-select"
                value={selectedDocumentType ?? document.detectedDocumentType ?? ""}
                disabled={disabled}
                onChange={(event) => callbacks?.onDocumentTypeChange?.(event.target.value)}
                className={FORM_INPUT}
              >
                {docTypeOptions.map((opt) => (
                  <option key={opt} value={opt}>
                    {opt}
                  </option>
                ))}
              </select>
              <span className="mt-1.5 block text-11 text-neutral-400">
                Changing the document type will trigger re-extraction with new fields.
              </span>
            </label>
          </div>

          <fieldset
            disabled={disabled}
            data-testid="review-fields"
            className="m-0 border-0 p-0 disabled:opacity-60"
          >
            {scalarFields.length > 0 && (
              <>
                <div className={SECTION_TITLE}>Extracted Data</div>
                {scalarFields.map((field) => (
                  <FieldRow key={field.name} field={field} />
                ))}
              </>
            )}
            {arrayFields.length > 0 && (
              <>
                <div className="my-4 h-px bg-neutral-100" />
                <div className={SECTION_TITLE}>Line Items</div>
                {arrayFields.map((field) => (
                  <FieldRow key={field.name} field={field} />
                ))}
              </>
            )}
          </fieldset>
        </div>
        {!hideActions && (
          <div
            data-testid="review-action-bar"
            className="flex flex-shrink-0 gap-2.5 border-t border-neutral-200 bg-card px-6 py-4"
          >
            {flagged ? (
              <button
                type="submit"
                data-testid="resolve-button"
                disabled={disabled || submitting}
                className="inline-flex h-[38px] flex-1 items-center justify-center gap-1.5 rounded-md border-0 bg-success px-5 text-13 font-semibold text-white transition-colors hover:bg-success-strong disabled:cursor-not-allowed disabled:opacity-60"
              >
                Resolve
              </button>
            ) : (
              <button
                type="submit"
                data-testid="approve-button"
                disabled={disabled || submitting}
                className="inline-flex h-[38px] flex-1 items-center justify-center gap-1.5 rounded-md border-0 bg-success px-5 text-13 font-semibold text-white transition-colors hover:bg-success-strong disabled:cursor-not-allowed disabled:opacity-60"
              >
                Approve
              </button>
            )}
            <button
              type="button"
              data-testid="reject-button"
              onClick={onReject}
              disabled={disabled || submitting}
              className="inline-flex h-[38px] items-center justify-center gap-1.5 rounded-md border border-[#fecaca] bg-card px-5 text-13 font-semibold text-danger transition-colors hover:bg-[#fef2f2] disabled:cursor-not-allowed disabled:opacity-50"
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
    return <FieldArrayTable field={field} />;
  }

  if (upper === "ENUM") {
    const values = field.enumValues ?? [];
    return (
      <label data-testid={`field-${field.name}`} className="mb-3.5 block">
        <span className="mb-1 block text-12 font-semibold text-neutral-700">{field.name}</span>
        <select
          data-testid={`input-${field.name}`}
          {...register(field.name)}
          aria-required={field.required}
          className={FORM_INPUT}
        >
          <option value="">— select —</option>
          {values.map((v) => (
            <option key={v} value={v}>
              {v}
            </option>
          ))}
        </select>
        {errorMsg && (
          <span
            role="alert"
            data-testid={`error-${field.name}`}
            className="mt-1 block text-11 text-danger"
          >
            {errorMsg}
          </span>
        )}
      </label>
    );
  }

  const inputType = upper === "DATE" ? "date" : "text";
  return (
    <label data-testid={`field-${field.name}`} className="mb-3.5 block">
      <span className="mb-1 block text-12 font-semibold text-neutral-700">{field.name}</span>
      <input
        type={inputType}
        data-testid={`input-${field.name}`}
        {...register(field.name)}
        aria-required={field.required}
        inputMode={upper === "DECIMAL" ? "decimal" : undefined}
        className={FORM_INPUT}
      />
      {errorMsg && (
        <span
          role="alert"
          data-testid={`error-${field.name}`}
          className="mt-1 block text-11 text-danger"
        >
          {errorMsg}
        </span>
      )}
    </label>
  );
}
