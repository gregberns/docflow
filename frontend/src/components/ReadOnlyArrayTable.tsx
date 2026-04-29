import type { FieldSchema } from "../types/schema";
import { formatDisplay } from "../util/formatters";

interface ReadOnlyArrayTableProps {
  name: string;
  itemFields: FieldSchema[];
  rows: ReadonlyArray<Record<string, unknown>>;
}

export function ReadOnlyArrayTable({ name, itemFields, rows }: ReadOnlyArrayTableProps) {
  return (
    <table data-testid="readonly-array" data-field-name={name}>
      <thead>
        <tr>
          {itemFields.map((field) => (
            <th key={field.name} scope="col">
              {field.name}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 ? (
          <tr data-testid="readonly-array-empty">
            <td colSpan={itemFields.length}>No rows</td>
          </tr>
        ) : (
          rows.map((row, rowIndex) => (
            <tr key={rowIndex} data-testid="readonly-array-row">
              {itemFields.map((field) => (
                <td key={field.name} data-field={field.name}>
                  {formatDisplay(row[field.name])}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  );
}
