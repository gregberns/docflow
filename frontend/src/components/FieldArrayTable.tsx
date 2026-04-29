import { useFieldArray, useFormContext, type Control } from "react-hook-form";
import type { FieldSchema } from "../types/schema";

type FormValues = Record<string, unknown>;

interface FieldArrayTableProps {
  field: FieldSchema;
}

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
