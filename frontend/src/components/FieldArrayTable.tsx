import { useFieldArray, useFormContext, type Control } from "react-hook-form";
import type { FieldSchema } from "../types/schema";

type FormValues = Record<string, unknown>;

interface FieldArrayTableProps {
  field: FieldSchema;
}

const HEADER_CELL =
  "border-b border-neutral-200 bg-table-head px-2 py-1.5 text-left text-10 font-semibold uppercase tracking-[0.3px] text-neutral-500";

const INPUT_BASE =
  "w-full rounded border border-transparent bg-transparent px-1.5 py-0.5 text-12 text-brand-navy outline-none hover:border-neutral-200 focus:border-brand-blue focus:bg-card focus:shadow-[0_0_0_2px_rgba(108,155,255,0.1)]";

export function FieldArrayTable({ field }: FieldArrayTableProps) {
  const itemFields = field.itemFields ?? [];
  const { register, control } = useFormContext<FormValues>();
  // RHF's useFieldArray expects a typed path against the form shape; FormValues is a
  // generic record so we narrow the control to the dynamic field name without losing runtime safety.
  const {
    fields: rows,
    append,
    remove,
  } = useFieldArray({
    control: control as Control<FormValues>,
    name: field.name as never,
  });

  return (
    <div data-testid={`field-${field.name}`} className="mb-3.5">
      <span className="mb-3 block text-11 font-bold uppercase tracking-[0.5px] text-neutral-500">
        {field.name}
      </span>
      <table data-testid={`field-array-${field.name}`} className="w-full border-collapse text-12">
        <thead>
          <tr>
            {itemFields.map((child) => (
              <th key={child.name} className={HEADER_CELL}>
                {child.name}
              </th>
            ))}
            <th aria-label="Row controls" className={`${HEADER_CELL} w-8`} />
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr
              key={row.id}
              data-testid={`field-array-${field.name}-row`}
              className="[&>td]:border-b [&>td]:border-neutral-100"
            >
              {itemFields.map((child) => (
                <td key={child.name} className="px-2 py-1.5 align-middle">
                  <input
                    data-testid={`input-${field.name}-${index}-${child.name}`}
                    {...register(`${field.name}.${index}.${child.name}` as never)}
                    className={INPUT_BASE}
                  />
                </td>
              ))}
              <td className="px-2 py-1.5 text-right align-middle">
                <button
                  type="button"
                  data-testid={`remove-row-${field.name}-${index}`}
                  onClick={() => remove(index)}
                  className="text-11 font-medium text-neutral-500 transition-colors hover:text-danger"
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
        className="mt-2 inline-flex h-7 items-center rounded-md border border-neutral-200 bg-card px-3 text-11 font-semibold text-neutral-700 transition-colors hover:border-brand-blue hover:text-brand-blue"
      >
        Add row
      </button>
    </div>
  );
}
