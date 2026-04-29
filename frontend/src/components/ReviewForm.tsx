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
  callbacks?: ReviewFormCallbacks;
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

export function ReviewForm({
  document,
  fields,
  docTypeOptions,
  flagged,
  disabled,
  hideActions,
  isSubmitting,
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
            onChange={(event) => callbacks?.onDocumentTypeChange?.(event.target.value)}
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
              onClick={onReject}
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
    return <FieldArrayTable field={field} />;
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
